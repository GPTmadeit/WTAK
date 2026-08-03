package com.atakwatch.minimap.data

import android.content.Context
import android.util.Log
import com.atakwatch.minimap.location.LocationEngine
import com.atakwatch.minimap.sensors.HeadingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * GPS and compass, owned at app scope.
 *
 * These used to belong to the map screen, which meant your own position stopped
 * updating the moment you opened Contacts, GeoChat or the radar — the transports
 * stayed up and kept broadcasting, but they broadcast a position that had
 * stopped moving. Anything that reads [CotRepository.self] now gets the same
 * live fix regardless of which screen is in front.
 *
 * Ownership still hands off cleanly: with background tracking on, the foreground
 * service is the single GPS owner and this stays idle, so the receiver never
 * sees two devices claiming the same UID.
 */
object Positioning {

    private const val TAG = "Positioning"

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var engine: LocationEngine? = null
    private var compass: HeadingProvider? = null
    private var collector: Job? = null

    /** Latest settings, so a fix arriving mid-change is stamped with current identity. */
    @Volatile private var settings: Settings = Settings()

    private val _heading = MutableStateFlow<Float?>(null)
    /** Smoothed compass heading in degrees true, or null with no magnetometer. */
    val heading: StateFlow<Float?> = _heading.asStateFlow()

    val isRunning: Boolean get() = engine != null

    /**
     * Reconcile the sensors with settings. Call while the app is interactive;
     * call [stop] when it isn't. Safe to call repeatedly.
     */
    @Synchronized
    fun apply(context: Context, s: Settings) {
        settings = s
        // The tracking service is the single GPS owner while it runs.
        if (s.backgroundTracking) { stop(); return }
        if (engine != null) return

        val app = context.applicationContext
        val loc = LocationEngine(app)
        val hdg = HeadingProvider(app)
        engine = loc
        compass = hdg

        collector = scope.launch {
            launch {
                loc.location.collect { fix ->
                    fix ?: return@collect
                    CotRepository.setSelf(SelfEventFactory.build(app, fix, settings))
                    // Keep the watch-face tile current (throttled internally).
                    runCatching {
                        com.atakwatch.minimap.tile.TileSnapshotWriter.update(
                            app, settings.coordFormat, settings.imperialUnits,
                        )
                    }
                }
            }
            launch { hdg.heading.collect { _heading.value = it } }
        }

        runCatching { loc.start() }.onFailure { Log.w(TAG, "gps: ${it.message}") }
        runCatching { hdg.start() }.onFailure { Log.w(TAG, "compass: ${it.message}") }
    }

    @Synchronized
    fun stop() {
        collector?.cancel(); collector = null
        runCatching { compass?.stop() }; compass = null
        runCatching { engine?.stop(); engine?.destroy() }; engine = null
        _heading.value = null
    }

    /**
     * Always-on display: drop GPS to its low-power cadence and shut the compass
     * down entirely — the screen only refreshes about once a minute, so a live
     * heading buys nothing and costs a sensor.
     */
    @Synchronized
    fun setAmbient(ambient: Boolean) {
        engine?.setAmbient(ambient)
        if (ambient) compass?.stop() else compass?.start()
    }
}
