package com.atakwatch.minimap.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Compass heading from the fused rotation-vector sensor. Registered only while
 * the map is on screen (lifecycle-scoped) and at SENSOR_DELAY_UI, so it costs
 * nothing when you're on another screen or the watch is asleep. Output is
 * low-pass filtered (with wrap-around handling) to avoid jitter and needless
 * map redraws.
 */
class HeadingProvider(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var smoothed = Float.NaN

    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading.asStateFlow()

    val isAvailable: Boolean get() = rotationVector != null

    fun start() {
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val azimuth = ((Math.toDegrees(orientation[0].toDouble()).toFloat()) + 360f) % 360f
        smoothed = if (smoothed.isNaN()) azimuth else lowPass(azimuth, smoothed)
        _heading.value = smoothed
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Circular low-pass so 359°→1° doesn't spin the needle the long way round. */
    private fun lowPass(target: Float, prev: Float, alpha: Float = 0.15f): Float {
        var delta = target - prev
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        if (abs(delta) < 0.3f) return prev
        return ((prev + alpha * delta) + 360f) % 360f
    }
}
