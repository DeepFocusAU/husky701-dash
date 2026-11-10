// Simulator.kt - port of the ESP32 dash's simulator.cpp.
//
// Runs the same ~70 s scripted ride and builds real wire frames, which go through the
// real FrameParser, so CRC checks, the drop counter and NO LINK are exercised too.
package com.husky701.dash

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class Simulator(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private val kmhPerKrpm = floatArrayOf(0f, 9.5f, 13f, 16f, 19f, 21.5f, 23.5f)

    private var startMs = clock()
    private var lastFrameMs = startMs
    private var seq = 0
    private var frameCount = 0L

    private var rpm = 0f
    private var gear = 0
    private var throttle = 0f
    private var coolantC = 35f
    private var vbat = 12.6f
    private var flags = 0
    private var courseDeg = 0f
    private var latDeg = -33.899
    private var lonDeg = 151.234

    var phaseName = "key on"
        private set

    fun restart() {
        startMs = clock()
        lastFrameMs = startMs
        coolantC = 35f
    }

    /** Feeds any frames that are due into the parser. */
    fun update(parser: FrameParser) {
        val now = clock()
        // Called once per screen refresh (16.7 ms at 60 Hz), so catch up on every
        // 20 ms frame that's due to hold 50 frames/s. After a long pause, skip ahead.
        if (now - lastFrameMs > 500) lastFrameMs = now - DashConfig.SIM_FRAME_MS
        while (now - lastFrameMs >= DashConfig.SIM_FRAME_MS) {
            lastFrameMs += DashConfig.SIM_FRAME_MS
            step(DashConfig.SIM_FRAME_MS / 1000f, (lastFrameMs - startMs) % DashConfig.SIM_LOOP_MS)
            emit(parser, lastFrameMs)
        }
    }

    private fun noise(a: Float) = a * (Random.nextFloat() * 2f - 1f)
    private fun approach(cur: Float, target: Float, dt: Float, tau: Float) = cur + (target - cur) * min(1f, dt / tau)

    private fun step(dt: Float, tMs: Long) {
        val t = tMs / 1000f
        var rpmTarget = 0f
        var coolTarget = 88f
        var running = true
        var clutch = false
        var standUp = true
        var map2 = false
        var killRun = true
        throttle = 0f

        when {
            t < 5f -> { phaseName = "key on"; running = false; standUp = false; gear = 0; if (t < 0.5f) coolantC = 35f }
            t < 6f -> { phaseName = "cranking"; standUp = false; gear = 0; rpmTarget = 1700f; clutch = true }
            t < 14f -> { phaseName = "idle"; gear = 0; standUp = t > 12f; rpmTarget = 1500f + noise(40f) }
            t < 38f -> {
                phaseName = "accelerate"
                val st = t - 14f
                gear = min(6, 1 + (st / 4f).toInt())
                val f = (st % 4f) / 4f
                if (f < 0.07f && gear > 1) { clutch = true; rpmTarget = 4200f } else {
                    val peak = if (gear <= 3) 7900f else 6600f
                    rpmTarget = 3800f + f * (peak - 3800f)
                    throttle = 0.85f + noise(0.05f)
                }
                coolTarget = 92f
            }
            t < 41f -> { phaseName = "lugging"; gear = 6; rpmTarget = 2600f; throttle = 0.70f + noise(0.03f) }
            t < 48f -> { phaseName = "cruise"; gear = 6; map2 = true; rpmTarget = 4800f + 300f * sin(t * 0.8f); throttle = 0.32f + noise(0.03f) }
            t < 56f -> {
                phaseName = "downshift"
                val st = t - 48f
                gear = max(2, 6 - (st / 1.6f).toInt())
                val f = (st % 1.6f) / 1.6f
                clutch = f < 0.15f
                rpmTarget = if (clutch) 5600f else 5200f - f * 2200f
            }
            t < 63f -> { phaseName = "hot idle"; gear = 0; rpmTarget = 1550f + noise(50f); coolTarget = 115f }
            else -> { phaseName = "engine off"; running = false; killRun = false; standUp = false; gear = 0; coolTarget = 80f }
        }
        if (!running) rpmTarget = 0f

        rpm = approach(rpm, rpmTarget, dt, 0.18f)
        if (rpm < 30f) rpm = 0f
        coolantC = approach(coolantC, coolTarget, dt, if (coolTarget > coolantC) 6f else 25f)

        var vTarget = 12.6f
        if (phaseName == "cranking") vTarget = 10.9f
        else if (running && rpm > 1000f) vTarget = 14.2f
        if (t >= 43f && t < 48f) vTarget = 11.8f
        else if (t >= 48f && t < 54f) vTarget = 15.4f
        else if (t >= 63f) vTarget = 11.6f
        vbat = approach(vbat, vTarget + noise(0.03f), dt, 0.4f)

        flags = Telem.FLAG_CAN_OK
        if (clutch) flags = flags or Telem.FLAG_CLUTCH_IN
        if (killRun) flags = flags or Telem.FLAG_KILL_RUN
        if (standUp) flags = flags or Telem.FLAG_STAND_UP
        if (map2) flags = flags or Telem.FLAG_MAP2
        if (throttle > 0.02f) flags = flags or Telem.FLAG_THROTTLE_OPEN
    }

    private fun emit(parser: FrameParser, now: Long) {
        val kmh = if (gear > 0 && flags and Telem.FLAG_CLUTCH_IN == 0) kmhPerKrpm[gear] * rpm / 1000f else 0f

        val simMin = 10 * 60 + 30 + ((now - startMs) / 60000).toInt()
        val dtS = DashConfig.SIM_FRAME_MS / 1000f
        val tS = (now - startMs) / 1000f
        courseDeg = ((courseDeg + (6f + 18f * sin(tS * 0.13f)) * dtS) + 360f) % 360f
        val meters = kmh / 3.6f * dtS
        val radC = Math.toRadians(courseDeg.toDouble())
        latDeg += meters * cos(radC) / 111319.5
        lonDeg += meters * sin(radC) / (111319.5 * cos(Math.toRadians(latDeg)))
        if (abs(latDeg + 33.899) > 0.045 || abs(lonDeg - 151.234) > 0.05) { latDeg = -33.899; lonDeg = 151.234 }
        val fix = tS > 6f

        val p = Telemetry(
            seq = seq and 0xFF,
            rpm = rpm.toInt(),
            gear = gear,
            throttle = (throttle * 255f).coerceIn(0f, 255f).toInt(),
            coolantDc = (coolantC * 10f).roundToInt(),
            wheelRear = (kmh / DashConfig.WHEEL_KMH_PER_COUNT).toInt(),
            wheelFront = (kmh / DashConfig.WHEEL_KMH_PER_COUNT * 0.985f).toInt(),
            vbatMv = (vbat * 1000f).roundToInt(),
            flags = flags,
            clockHour = (simMin / 60) % 24,
            clockMin = simMin % 60,
            latE7 = (latDeg * 1e7).roundToInt(),
            lonE7 = (lonDeg * 1e7).roundToInt(),
            speedDkmh = (kmh * 10f).roundToInt(),
            courseDd = (courseDeg * 10f).roundToInt(),
            sats = if (fix) 9 else 2,
            fix = if (fix) 1 else 0,
            day = if (fix) 9806 else 0,
            altM = (120f + 60f * sin(tS * 0.05f)).roundToInt(),
            hdopX10 = 12,
        )
        seq++
        val frame = p.encodeFrame()
        frameCount++
        if (DashConfig.SIM_FAULT_CRC_EVERY > 0 && frameCount % DashConfig.SIM_FAULT_CRC_EVERY == 0L) {
            frame[2 + Telem.PAYLOAD_LEN] = (frame[2 + Telem.PAYLOAD_LEN].toInt() xor 0x5A).toByte()
        }
        if (DashConfig.SIM_FAULT_DROP_EVERY > 0 && frameCount % DashConfig.SIM_FAULT_DROP_EVERY == 0L) return
        parser.feed(frame)
    }
}
