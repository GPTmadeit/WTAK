package com.atakwatch.minimap.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the outcome of an install session started by [Updater].
 *
 * The interesting case is [PackageInstaller.STATUS_PENDING_USER_ACTION]: the
 * platform has staged the APK and wants the wearer to confirm, handing back an
 * intent to show. That confirmation is the whole point — an app that could
 * replace itself silently would be a far worse thing to put on someone's wrist
 * than one that asks.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Updater.onInstallResult(false, "No confirmation screen was offered")
                    return
                }
                // Started from a receiver, so it needs its own task.
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure {
                        Log.w(TAG, "no installer UI: ${it.message}")
                        Updater.onInstallResult(
                            false,
                            "This watch has no installer screen — install over adb",
                        )
                    }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "update installed")
                Updater.onInstallResult(true, null)
            }

            else -> {
                Log.w(TAG, "install failed ($status): $message")
                Updater.onInstallResult(false, explain(status, message))
            }
        }
    }

    private fun explain(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Cancelled"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Blocked by the system"
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Conflicts with the installed app — signatures may differ"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Not compatible with this watch"
        PackageInstaller.STATUS_FAILURE_INVALID -> "The APK was rejected as invalid"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
        else -> message?.takeIf { it.isNotBlank() } ?: "Install failed"
    }

    companion object {
        private const val TAG = "InstallReceiver"
        const val ACTION_INSTALL_STATUS = "com.atakwatch.minimap.INSTALL_STATUS"
    }
}
