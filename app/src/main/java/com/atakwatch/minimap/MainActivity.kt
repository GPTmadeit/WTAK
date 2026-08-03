package com.atakwatch.minimap

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import com.atakwatch.minimap.data.Positioning
import com.atakwatch.minimap.service.TrackingService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.MotionScheme
import com.atakwatch.minimap.net.CertEnrollment
import com.atakwatch.minimap.ui.ATAKWatchRoot
import com.atakwatch.minimap.ui.AmbientState
import com.atakwatch.minimap.ui.LocalAmbientState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var ambient by mutableStateOf(AmbientState())

    /**
     * Always-on display. Wear keeps the activity resumed but throttles updates
     * to roughly one per minute, so we publish the state and let the UI drop to
     * a low-power presentation instead of rendering the live map continuously.
     */
    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(details: AmbientLifecycleObserver.AmbientDetails) {
                ambient = ambient.copy(
                    isAmbient = true,
                    lowBitAmbient = details.deviceHasLowBitAmbient,
                    burnInProtection = details.burnInProtectionRequired,
                )
            }

            override fun onUpdateAmbient() {
                ambient = ambient.copy(tick = ambient.tick + 1)
            }

            override fun onExitAmbient() {
                ambient = ambient.copy(isAmbient = false)
            }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        observeBackgroundTracking()
        observePositioning()
        intent?.let { handleDebugExtras(it) }
        setContent {
            // Wear Material 3 Expressive — the Wear OS 6 / Pixel Watch 4 design
            // system. The expressive motion scheme is opted into explicitly:
            // every animation in the app resolves its curves from here, so the
            // whole UI moves with the platform's weight instead of with
            // durations somebody guessed.
            MaterialTheme(motionScheme = MotionScheme.expressive()) {
                CompositionLocalProvider(LocalAmbientState provides ambient) {
                    ATAKWatchRoot()
                }
            }
        }
    }

    /**
     * Start/stop the tracking service from the setting, app-wide rather than
     * per-screen. Collected only while STARTED for two reasons: the platform
     * forbids launching a foreground service from the background, and when the
     * activity stops we deliberately leave a running service alone — that is
     * the whole point of background tracking.
     */
    private fun observeBackgroundTracking() {
        val repo = (application as ATAKWatchApp).settings
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.settings
                    .distinctUntilChanged()
                    .collect { s ->
                        runCatching {
                            if (s.backgroundTracking) TrackingService.start(this@MainActivity)
                            else TrackingService.stop(this@MainActivity)
                        }.onFailure { Log.w("MainActivity", "tracking service: ${it.message}") }

                        // Transports live here, not in the map screen, so they
                        // survive navigating to Contacts, GeoChat or Settings.
                        runCatching {
                            com.atakwatch.minimap.net.Transports.apply(this@MainActivity, s)
                        }.onFailure { Log.w("MainActivity", "transports: ${it.message}") }
                    }
            }
        }
    }

    /**
     * GPS and compass, app-wide rather than per-screen.
     *
     * Scoped to RESUMED, not STARTED: sensors should be alive exactly while the
     * app is in front of the wearer, and no longer. Anything that needs a
     * position with the screen off turns on background tracking, which hands
     * ownership to the foreground service instead.
     */
    private fun observePositioning() {
        val repo = (application as ATAKWatchApp).settings
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    repo.settings.distinctUntilChanged().collect { s ->
                        runCatching { Positioning.apply(this@MainActivity, s) }
                            .onFailure { Log.w("MainActivity", "positioning: ${it.message}") }
                    }
                } finally {
                    Positioning.stop()
                }
            }
        }
    }

    // `am start` on an already-running activity delivers here, not onCreate —
    // without this the extras silently do nothing on relaunch.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugExtras(intent)
    }

    /**
     * Debug/testing hooks: `adb shell am start … --ez enable_mesh true
     * --ez enable_takserver true --ez enable_tls true --ez enroll_now true`
     * flips networking settings without taps on the watch UI (see README).
     *
     * Debug builds only. This activity is exported (it is the launcher), so in a
     * release build any installed app could otherwise start it with extras and
     * silently change where this device transmits its position.
     */
    private fun handleDebugExtras(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val repo = (application as ATAKWatchApp).settings
        if (intent.hasExtra("enable_mesh")) {
            val v = intent.getBooleanExtra("enable_mesh", false)
            lifecycleScope.launch { repo.setCotMesh(v) }
        }
        if (intent.hasExtra("enable_takserver")) {
            val v = intent.getBooleanExtra("enable_takserver", false)
            lifecycleScope.launch { repo.setTakServer(v) }
        }
        if (intent.hasExtra("enable_tls")) {
            val v = intent.getBooleanExtra("enable_tls", false)
            lifecycleScope.launch { repo.setTakTls(v) }
        }
        if (intent.getBooleanExtra("enroll_now", false)) {
            lifecycleScope.launch { CertEnrollment.enroll(this@MainActivity) }
        }
    }
}
