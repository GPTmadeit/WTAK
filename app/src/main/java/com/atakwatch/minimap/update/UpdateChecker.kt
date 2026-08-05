package com.atakwatch.minimap.update

import android.util.Log
import com.atakwatch.minimap.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub what the latest release is.
 *
 * Reads the public Releases API — no token, no account, nothing to configure —
 * and returns the signed APK attached to it. The endpoint is fixed to this
 * project's own repository rather than being configurable: an update source is
 * the one setting where "let the user point it anywhere" is a way to install
 * someone else's code.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** The repository releases are published from. */
    const val REPO = "GPTmadeit/WTAK"

    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val TIMEOUT_MS = 15_000

    /** Guards against a hostile or mistaken asset; a watch build is ~11 MB. */
    private const val MAX_APK_BYTES = 100L * 1024 * 1024

    data class Release(
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val apkBytes: Long,
        val pageUrl: String,
    ) {
        val isNewerThanInstalled: Boolean
            get() = Version.isNewer(versionName, BuildConfig.VERSION_NAME)
    }

    sealed interface Result {
        data class UpdateAvailable(val release: Release) : Result
        data object UpToDate : Result
        /** A release exists but carries no APK we can install. */
        data object NoAsset : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun latest(): Result = withContext(Dispatchers.IO) {
        runCatching { fetch() }
            .onFailure { Log.w(TAG, "check failed: ${it.message}") }
            .getOrElse { Result.Failed(it.message ?: "no network") }
    }

    private fun fetch(): Result {
        val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // GitHub asks for an explicit API version and a User-Agent; without
            // the latter it answers 403.
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "WTAK/${BuildConfig.VERSION_NAME}")
        }

        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return Result.Failed(
                    when (code) {
                        HttpURLConnection.HTTP_NOT_FOUND -> "No releases published"
                        403 -> "GitHub rate limit — try again later"
                        else -> "GitHub returned $code"
                    }
                )
            }

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").ifBlank { json.optString("name") }
            val version = Version.parse(tag)
                ?: return Result.Failed("Unreadable release tag '$tag'")

            if (!Version.isNewer(tag, BuildConfig.VERSION_NAME)) return Result.UpToDate

            // Pick the APK. A release may also carry checksums or notes.
            val assets = json.optJSONArray("assets")
            var url: String? = null
            var size = 0L
            for (i in 0 until (assets?.length() ?: 0)) {
                val asset = assets!!.getJSONObject(i)
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                url = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                size = asset.optLong("size")
                break
            }
            val apkUrl = url ?: return Result.NoAsset
            if (!apkUrl.startsWith("https://")) return Result.Failed("Asset is not served over HTTPS")
            if (size > MAX_APK_BYTES) return Result.Failed("Asset is implausibly large")

            return Result.UpdateAvailable(
                Release(
                    versionName = version.toString(),
                    notes = json.optString("body").trim(),
                    apkUrl = apkUrl,
                    apkBytes = size,
                    pageUrl = json.optString("html_url"),
                )
            )
        } finally {
            connection.disconnect()
        }
    }
}
