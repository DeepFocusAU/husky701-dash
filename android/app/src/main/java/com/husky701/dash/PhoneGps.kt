// PhoneGps.kt - position, speed and course from the phone's own GPS.
//
// The Note 10's GNSS (GPS, GLONASS, Galileo, BeiDou) is far better than the CAN node's
// NEO-6M, so it is the main source. The node's GPS is used only when the phone has no fix.
package com.husky701.dash

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock

/** One GPS reading, from whichever source supplied it. */
data class GpsSample(
    val fix: Boolean = false,
    val latE7: Int = 0,
    val lonE7: Int = 0,
    val kmh: Float = 0f,
    val courseDeg: Float = 0f,
    val courseValid: Boolean = false,
    val sats: Int = 0,
    val altM: Int = 0,
    val accuracyM: Float = 0f,
    val source: String = "none",
) {
    companion object {
        /** From the CAN node's (or simulator's) telemetry. */
        fun fromTelemetry(t: Telemetry, alive: Boolean, source: String) = GpsSample(
            fix = alive && t.gpsFix,
            latE7 = t.latE7, lonE7 = t.lonE7,
            kmh = t.speedDkmh / 10f,
            courseDeg = t.courseDd / 10f,
            courseValid = alive && t.gpsFix,
            sats = t.sats,
            altM = t.altM,
            accuracyM = t.hdopX10 * 0.5f,          // rough: HDOP x 5 m
            source = source,
        )
    }
}

@SuppressLint("MissingPermission")   // started only once location permission is granted
class PhoneGps(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var last: Location? = null
    private var satsUsed = 0
    private var satsSeen = 0
    private var running = false

    var enabled = false
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) { last = l }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { enabled = true }
        override fun onProviderDisabled(provider: String) { enabled = false }
    }

    private val gnssCb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satsUsed = used
            satsSeen = status.satelliteCount
        }
    }

    fun start() {
        if (running) return
        running = true
        enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, listener, Looper.getMainLooper())
        lm.registerGnssStatusCallback(gnssCb, android.os.Handler(Looper.getMainLooper()))
    }

    fun stop() {
        if (!running) return
        running = false
        lm.removeUpdates(listener)
        lm.unregisterGnssStatusCallback(gnssCb)
    }

    val satellitesSeen get() = satsSeen

    fun sample(): GpsSample {
        val l = last ?: return GpsSample(sats = satsUsed, source = "phone")
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        val fix = ageMs < DashConfig.GPS_FIX_MAX_AGE_MS
        val kmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
        return GpsSample(
            fix = fix,
            latE7 = Math.round(l.latitude * 1e7).toInt(),
            lonE7 = Math.round(l.longitude * 1e7).toInt(),
            kmh = if (fix) kmh else 0f,
            courseDeg = if (l.hasBearing()) l.bearing else 0f,
            courseValid = fix && l.hasBearing(),
            sats = satsUsed,
            altM = if (l.hasAltitude()) Math.round(l.altitude).toInt() else 0,
            accuracyM = if (l.hasAccuracy()) l.accuracy else 0f,
            source = "phone",
        )
    }
}
