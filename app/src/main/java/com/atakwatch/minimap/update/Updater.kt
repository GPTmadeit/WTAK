package com.atakwatch.minimap.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates the app from its own GitHub releases, on the watch, with no phone and
 * no cable.
 *
 * Sideloaded builds have no store behind them, so without this the only way to
 * take a fix is to find a computer and an adb cable — which is a poor answer
 * when the thing you are updating is strapped to your wrist in the field.
 *
 * The flow is deliberately explicit at every step: check, show what changed,
 * download, verify, then hand a real install to the platform for confirmation.
 * Nothing is installed without the wearer saying so.
 *
 * ### Trust
 *
 * Android will refuse an update whose signing certificate differs from the
 * installed app's, which is the actual security boundary here. This checks the
 * same thing *before* installing anyway, because doing so turns a cryptic
 * platform failure into a sentence that says what is wrong — most often "you
 * are running a debug build and this is the release APK".
 */
object Updater {

    private const val TAG = "Updater"
    private const val TIMEOUT_MS = 20_000

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: UpdateChecker.Release) : State
        data class Downloading(val release: UpdateChecker.Release, val fraction: Float) : State
        data class Verifying(val release: UpdateChecker.Release) : State
        /** Handed to the platform; the wearer is being asked to confirm. */
        data class Confirming(val release: UpdateChecker.Release) : State
        /**
         * The watch has not been told this app may install packages. Android
         * blocks the install at the very last step, after the download, with a
         * dialog that does not say which app it is talking about — so this is
         * checked up front and explained instead.
         */
        data class NeedsPermission(val release: UpdateChecker.Release) : State
        data class Failed(val reason: String) : State
    }

    /** Whether the wearer has allowed this app to install packages. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * The system screen where "install unknown apps" is granted, or null if this
     * watch has no such screen — in which case adb is the only route.
     */
    fun unknownSourcesSettings(context: Context): Intent? {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        )
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Update work runs here, not in the caller's composition.
     *
     * A screen's `rememberCoroutineScope` dies the moment that screen leaves
     * the composition — including when this object's own state change removes
     * the button that started the work. Downloading and installing an APK must
     * outlive scrolling, recomposition and navigating away, so it is owned at
     * app scope and the UI only ever triggers it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** At most one check or install at a time; the UI has one button for each. */
    private var job: Job? = null

    fun reset() { _state.value = State.Idle }

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = State.Checking
            _state.value = when (val result = UpdateChecker.latest()) {
                is UpdateChecker.Result.UpdateAvailable -> State.Available(result.release)
                UpdateChecker.Result.UpToDate -> State.UpToDate
                UpdateChecker.Result.NoAsset -> State.Failed("Release has no APK attached")
                is UpdateChecker.Result.Failed -> State.Failed(result.reason)
            }
        }
    }

    /**
     * Download the release, verify it is genuinely an update to *this* app, and
     * ask the platform to install it.
     */
    fun install(context: Context, release: UpdateChecker.Release) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch { runInstall(app, release) }
    }

    private suspend fun runInstall(context: Context, release: UpdateChecker.Release) {
        val app = context.applicationContext

        // Checked before the download, not after: spending 10 MB of someone's
        // connection to then be refused is the wrong order to find out.
        if (!canInstall(app)) {
            _state.value = State.NeedsPermission(release)
            return
        }

        val apk = try {
            _state.value = State.Downloading(release, 0f)
            download(app, release) { fraction ->
                _state.value = State.Downloading(release, fraction)
            }
        } catch (e: CancellationException) {
            // Being cancelled is not a failure to report; it is the caller
            // going away. Reporting it puts coroutine plumbing on screen.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            _state.value = State.Failed("Download failed — ${e.message ?: "no network"}")
            return
        }

        _state.value = State.Verifying(release)
        val problem = verify(app, apk)
        if (problem != null) {
            apk.delete()
            _state.value = State.Failed(problem)
            return
        }

        _state.value = State.Confirming(release)
        runCatching { commit(app, apk) }
            .onFailure {
                Log.w(TAG, "install failed: ${it.message}")
                _state.value = State.Failed("Install failed — ${it.message}")
            }
    }

    // ---- download ------------------------------------------------------------

    private suspend fun download(
        context: Context,
        release: UpdateChecker.Release,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        // Only ever one staged APK; a half-finished earlier attempt is useless.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "wtak-${release.versionName}.apk")

        var url = URL(release.apkUrl)
        var connection = open(url)
        // Release assets redirect to a CDN, and HttpURLConnection will not
        // follow a redirect across protocols or hosts on its own.
        var redirects = 0
        while (connection.responseCode in 300..399 && redirects < 5) {
            val location = connection.getHeaderField("Location")
                ?: throw IllegalStateException("redirect without a location")
            connection.disconnect()
            url = URL(url, location)
            if (url.protocol != "https") throw IllegalStateException("redirected off HTTPS")
            connection = open(url)
            redirects++
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            val code = connection.responseCode
            connection.disconnect()
            throw IllegalStateException("server returned $code")
        }

        val expected = if (release.apkBytes > 0) release.apkBytes
        else connection.contentLengthLong
        try {
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (expected > 0) onProgress((written.toFloat() / expected).coerceIn(0f, 1f))
                    }
                    if (expected > 0 && written != expected) {
                        throw IllegalStateException("truncated at $written of $expected bytes")
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        target
    }

    private fun open(url: URL) = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", "WTAK")
    }

    // ---- verification --------------------------------------------------------

    /** Returns a human-readable problem, or null when the APK is safe to install. */
    private fun verify(context: Context, apk: File): String? {
        if (!apk.isFile || apk.length() == 0L) return "Downloaded file is empty"

        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, signingFlags())
            ?: return "Downloaded file is not a valid APK"

        if (archive.packageName != context.packageName) {
            return "That APK is ${archive.packageName}, not this app"
        }

        val installed = signatures(pm.getPackageInfo(context.packageName, signingFlags()))
        val candidate = signatures(archive)
        if (installed.isEmpty() || candidate.isEmpty()) return "Could not read signing certificates"
        if (installed.intersect(candidate).isEmpty()) {
            // The usual cause is a debug build trying to take a release APK.
            return "Signed with a different key — uninstall first, or use a matching build"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int = PackageManager.GET_SIGNING_CERTIFICATES

    @Suppress("DEPRECATION")
    private fun signatures(info: android.content.pm.PackageInfo?): Set<String> {
        val signing = info?.signingInfo ?: return emptySet()
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners
        else signing.signingCertificateHistory
        val digest = MessageDigest.getInstance("SHA-256")
        return certs.orEmpty().map { digest.digest(it.toByteArray()).toHex() }.toSet()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // ---- install -------------------------------------------------------------

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // An app replacing *itself* with a build signed by the same key
                // is the one case Android 12+ lets through without a second
                // confirmation dialog. The wearer already consented by tapping
                // install; this avoids sending them through a system screen
                // that, on Wear, is a dead end with no buttons on it.
                //
                // It is a request, not a guarantee — the platform answers with
                // STATUS_PENDING_USER_ACTION when it still wants a prompt, and
                // InstallReceiver shows it.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("wtak", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallReceiver::class.java)
                    .setAction(InstallReceiver.ACTION_INSTALL_STATUS)
                    .setPackage(context.packageName),
                // The platform fills in status extras, so this cannot be immutable.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
        Log.i(TAG, "install session $sessionId committed")
    }

    /** Reported by [InstallReceiver] once the platform has an answer. */
    internal fun onInstallResult(success: Boolean, message: String?) {
        _state.value = if (success) {
            // On success the process is normally replaced before this is seen.
            State.Idle
        } else {
            State.Failed(message?.takeIf { it.isNotBlank() } ?: "Install was cancelled")
        }
    }
}
