// PhoneCompass.kt - heading from the phone's own compass (rotation vector sensor), corrected to
// true north, for the compass rose and the map pointer.
package com.husky701.dash

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class PhoneCompass(ctx: Context, private val onHeading: (Float?) -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private var sx = 0f
    private var cy = 0f
    private var have = false
    private var declination = 0f
    private var declinationMs = 0L

    val available get() = sensor != null

    fun start() { sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }

    fun stop() { sm?.unregisterListener(this); onHeading(null) }

    /** Magnetic to true north, from the World Magnetic Model at this position. About 12.8° east around Sydney. */
    fun updateDeclination(latE7: Int, lonE7: Int, altM: Float, nowMs: Long) {
        if (declinationMs != 0L && nowMs - declinationMs < 60_000) return
        declinationMs = nowMs
        declination = GeomagneticField((latE7 / 1e7).toFloat(), (lonE7 / 1e7).toFloat(), altM, System.currentTimeMillis()).declination
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // The bike's forward direction, whatever the mount's tilt: the phone's top edge (+Y) when
        // it lies flat, the back of the phone (-Z) when it stands upright, and a mix of the two in
        // between. Both are taken into world east/north and added.
        val east = r[1] - r[2]
        val north = r[4] - r[5]
        if (hypot(east, north) < 0.3f) return          // no clear forward direction
        val a = atan2(east, north)
        val k = 0.15f                                    // smoothing
        if (!have) { sx = sin(a); cy = cos(a); have = true }
        else { sx += (sin(a) - sx) * k; cy += (cos(a) - cy) * k }
        val deg = Math.toDegrees(atan2(sx, cy).toDouble()).toFloat() + declination
        onHeading((deg % 360f + 360f) % 360f)
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
}
