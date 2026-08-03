package com.atakwatch.minimap.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.atakwatch.minimap.MainActivity
import com.atakwatch.minimap.R
import com.atakwatch.minimap.data.CotRepository
import com.atakwatch.minimap.data.SelfEventFactory
import com.atakwatch.minimap.data.Settings
import com.atakwatch.minimap.ATAKWatchApp
import com.atakwatch.minimap.location.LocationEngine
import com.atakwatch.minimap.net.CertStore
import com.atakwatch.minimap.net.CotMulticast
import com.atakwatch.minimap.net.TakClient
import com.atakwatch.minimap.data.MeshFormat
import com.atakwatch.minimap.net.CertEnrollment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps position tracking and CoT publishing alive when
 * the map screen isn't in front — the watch is on the wrist, screen off, and the
 * team still needs your PLI.
 *
 * Without this, Android freezes the process shortly after the activity stops and
 * the app silently disappears from the network. Declared with
 * `foregroundServiceType="location"` and backed by an ongoing notification, as
 * the platform requires for background location.
 */
class TrackingService : LifecycleService() {

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.atakwatch.minimap.START_TRACKING"
        const val ACTION_STOP = "com.atakwatch.minimap.STOP_TRACKING"

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var locationEngine: LocationEngine
    private var mesh: CotMulticast? = null
    private var takClient: TakClient? = null
    private var radio: com.atakwatch.minimap.net.meshtastic.MeshtasticLink? = null
    private var settings: Settings = Settings()
    private var running = false

    override fun onCreate() {
        super.onCreate()
        locationEngine = LocationEngine(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopTracking(); return START_NOT_STICKY }
            else -> startTracking()
        }
        // Tracking should resume if the process is killed and restarted.
        return START_STICKY
    }

    private fun startTracking() {
        if (running) return
        running = true

        startForegroundCompat()

        val repo = (application as ATAKWatchApp).settings

        // Publish the self PLI from every fix, exactly like the map screen does.
        locationEngine.location
            .onEach { loc ->
                loc ?: return@onEach
                CotRepository.setSelf(SelfEventFactory.build(this, loc, settings))
                // The tile must stay fresh while tracking runs with the app closed.
                com.atakwatch.minimap.tile.TileSnapshotWriter.update(
                    this, settings.coordFormat, settings.imperialUnits,
                )
            }
            .launchIn(lifecycleScope)

        // Keep transports in sync with settings for as long as we run.
        repo.settings
            .onEach { s ->
                val was = settings
                settings = s
                if (s.cotMesh != was.cotMesh || s.meshFormat != was.meshFormat || mesh == null) {
                    mesh?.stop()
                    if (s.cotMesh) {
                        mesh = CotMulticast(applicationContext).also {
                            it.start(proto = s.meshFormat == MeshFormat.TAK_PROTO) {
                                CotRepository.self.value
                            }
                        }
                    } else mesh = null
                }
                if (s.takServer != was.takServer || s.takServerHost != was.takServerHost ||
                    s.takTls != was.takTls || takClient == null
                ) {
                    takClient?.stop()
                    if (s.takServer) {
                        val useTls = s.takTls && CertStore.hasIdentity(this)
                        val hostPort = if (useTls) {
                            val cfg = CertEnrollment.loadConfig(this)
                            "${s.takServerHost.substringBefore(':')}:${cfg?.tlsPort ?: 8089}"
                        } else s.takServerHost
                        val ssl = if (useTls) runCatching { CertStore.sslContext(this) }.getOrNull() else null
                        takClient = TakClient().also { it.start(hostPort, ssl) { CotRepository.self.value } }
                    } else takClient = null
                }
                if (s.meshtastic != was.meshtastic ||
                    s.meshtasticAddress != was.meshtasticAddress || radio == null
                ) {
                    radio?.stop()
                    radio = if (s.meshtastic && s.meshtasticAddress.isNotBlank()) {
                        com.atakwatch.minimap.net.meshtastic.MeshtasticLink(this).also {
                            it.start(s.meshtasticAddress) { CotRepository.self.value }
                        }
                    } else null
                }
                updateNotification()
            }
            .launchIn(lifecycleScope)

        lifecycleScope.launch {
            settings = repo.settings.first()
            locationEngine.start()
            Log.i(TAG, "tracking started")
        }
    }

    private fun stopTracking() {
        Log.i(TAG, "tracking stopped")
        running = false
        locationEngine.stop()
        locationEngine.destroy()
        mesh?.stop(); mesh = null
        takClient?.stop(); takClient = null
        radio?.stop(); radio = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) {
            locationEngine.stop()
            locationEngine.destroy()
            mesh?.stop()
            takClient?.stop()
            radio?.stop()
            running = false
        }
        super.onDestroy()
    }

    // ---- notification ----

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val transports = buildList {
            if (settings.cotMesh) add("mesh")
            if (settings.takServer) add(if (settings.takTls) "TAK/TLS" else "TAK")
            if (settings.meshtastic && settings.meshtasticAddress.isNotBlank()) add("LoRa")
        }
        val detail = if (transports.isEmpty()) getString(R.string.tracking_local_only)
        else getString(R.string.tracking_sharing, transports.joinToString(" + "))

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentTitle(getString(R.string.tracking_title, settings.callsign))
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundCompat() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
    }

    private fun updateNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }
}
