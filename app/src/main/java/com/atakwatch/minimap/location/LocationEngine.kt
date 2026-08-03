package com.atakwatch.minimap.location

import android.content.Context
import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider

/**
 * A single, event-driven GPS source. Replaces the previous 1 Hz polling loop:
 * the OS pushes fixes to us (throttled by min-time / min-distance) and we expose
 * them as a [StateFlow]. One provider feeds the self marker, the HUD, CoT
 * publishing — instead of each polling independently.
 */
class LocationEngine(context: Context) : IMyLocationConsumer {

    private val provider = GpsMyLocationProvider(context).apply {
        // Battery-conscious cadence: at most 1 update/sec, and only after ~2 m of movement.
        locationUpdateMinTime = INTERACTIVE_MIN_TIME_MS
        locationUpdateMinDistance = INTERACTIVE_MIN_DISTANCE_M
    }

    private companion object {
        const val INTERACTIVE_MIN_TIME_MS = 1_000L
        const val INTERACTIVE_MIN_DISTANCE_M = 2f
        // Always-on display: the screen only refreshes about once a minute, so
        // there is no point burning GPS at the interactive rate.
        const val AMBIENT_MIN_TIME_MS = 30_000L
        const val AMBIENT_MIN_DISTANCE_M = 20f
    }

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        provider.startLocationProvider(this)
        provider.lastKnownLocation?.let { _location.value = it }
    }

    fun stop() {
        if (!started) return
        started = false
        provider.stopLocationProvider()
    }

    /**
     * Switch between the interactive and always-on cadences. Restarting the
     * provider is required for new min-time/min-distance values to take effect.
     */
    fun setAmbient(ambient: Boolean) {
        if (isAmbient == ambient) return
        isAmbient = ambient
        provider.locationUpdateMinTime =
            if (ambient) AMBIENT_MIN_TIME_MS else INTERACTIVE_MIN_TIME_MS
        provider.locationUpdateMinDistance =
            if (ambient) AMBIENT_MIN_DISTANCE_M else INTERACTIVE_MIN_DISTANCE_M
        if (started) {
            provider.stopLocationProvider()
            provider.startLocationProvider(this)
        }
    }

    private var isAmbient = false

    fun destroy() = provider.destroy()

    override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
        location?.let { _location.value = it }
    }
}
