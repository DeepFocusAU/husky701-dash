// DashLogic.kt - turns raw telemetry into what the dash shows, same rules as the ESP32 dash.
package com.husky701.dash

import kotlin.math.exp
import kotlin.math.max

enum class Level { NONE, COLD, GOOD, WARN, BAD }

/** On-screen taps, with the piezo switch's timing: a gesture ends TAP_GAP_MS after the last tap. */
class TapGesture(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private var count = 0
    private var lastMs = 0L

    fun onTouchDown() {
        val now = clock()
        if (now - lastMs >= DashConfig.TAP_QUIET_MS && count < 255) count++
        lastMs = now
    }

    /** Returns the tap count once a gesture has finished, 0 = none (or more than TAP_MAX). */
    fun poll(): Int {
        if (count == 0 || clock() - lastMs < DashConfig.TAP_GAP_MS) return 0
        val n = count
        count = 0
        return if (n <= DashConfig.TAP_MAX) n else 0
    }
}

class DashLogic(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    var tachoRpm = 0f; private set
    var peakRpm = 0f; private set
    var shiftUp = false; private set
    var shiftDown = false; private set
    var coolant = Level.NONE; private set
    var battery = Level.NONE; private set
    var speedKmh: Float? = null; private set
    var speedFromGps = false; private set

    private var lastMs = 0L
    private var peakMs = 0L
    private var lugSinceMs = 0L
    private var crankMs = -1_000_000L
    private var wasTurning = false

    fun update(t: Telemetry, alive: Boolean, gps: GpsSample) {
        val now = clock()
        val dt = if (lastMs == 0L) 0f else (now - lastMs).toFloat()
        lastMs = now

        val rpm = if (alive) t.rpm.toFloat() else 0f
        // Tacho follows rpm with a fast rise and a softer fall.
        val tau = if (rpm > tachoRpm) DashConfig.TACHO_RISE_MS else DashConfig.TACHO_FALL_MS
        tachoRpm += (rpm - tachoRpm) * (1f - exp(-dt / tau))
        if (tachoRpm >= peakRpm || now - peakMs > DashConfig.TACHO_PEAK_HOLD_MS) {
            peakRpm = tachoRpm
            peakMs = now
        }

        val gear = t.gear
        val clutch = t.has(Telem.FLAG_CLUTCH_IN)
        shiftUp = alive && t.rpm >= DashConfig.SHIFT_RPM

        val thrPct = t.throttle * 100 / 255
        val lugging = alive && gear != Telem.GEAR_UNKNOWN && gear >= DashConfig.LUG_MIN_GEAR && !clutch &&
            t.rpm in 1 until DashConfig.LUG_RPM && thrPct >= DashConfig.LUG_THROTTLE_PCT
        if (!lugging) lugSinceMs = 0L else if (lugSinceMs == 0L) lugSinceMs = now
        shiftDown = lugging && now - lugSinceMs >= DashConfig.LUG_HOLD_MS

        coolant = when (val c = if (alive) t.coolantC else null) {
            null -> Level.NONE
            else -> when {
                c >= DashConfig.COOLANT_WARN_C -> Level.BAD
                c >= DashConfig.COOLANT_HOT_C -> Level.WARN
                c < DashConfig.COOLANT_COLD_C -> Level.COLD
                else -> Level.GOOD
            }
        }

        // Low-voltage warnings wait VBAT_START_GRACE_MS after the engine starts turning.
        val turning = alive && t.rpm > 0
        if (turning && !wasTurning) crankMs = now
        wasTurning = turning
        val running = alive && t.rpm > 1000
        battery = when (val v = if (alive) t.vbatV else null) {
            null -> Level.NONE
            else -> {
                val inGrace = now - crankMs < DashConfig.VBAT_START_GRACE_MS
                when {
                    v > DashConfig.VBAT_HIGH_WARN -> Level.BAD
                    running && !inGrace && v < DashConfig.VBAT_LOW_WARN -> Level.BAD
                    !inGrace && v < DashConfig.VBAT_FLAT_WARN -> Level.WARN
                    else -> Level.GOOD
                }
            }
        }

        // Road speed: GPS when there's a fix (the phone's, normally), wheel speed otherwise.
        speedFromGps = gps.fix
        speedKmh = when {
            gps.fix -> gps.kmh.let { if (it < DashConfig.GPS_SPEED_MIN_KMH) 0f else it }
            !alive -> null
            else -> max(0f, (if (DashConfig.SPEED_FROM_FRONT) t.wheelFront else t.wheelRear) * DashConfig.WHEEL_KMH_PER_COUNT)
        }
    }
}
