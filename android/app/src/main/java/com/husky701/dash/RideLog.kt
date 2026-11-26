// RideLog.kt - records every real ride to a CSV file on the phone.
//
// A ride starts when live CAN data arrives with the engine turning, and ends after
// RIDE_END_IDLE_MS without CAN data or with the engine off (key-off, a long stop).
// SIM data is never recorded. One file per ride:
//   <app external files>/rides/ride_YYYYMMDD_HHMMSS.csv
//   adb pull /sdcard/Android/data/com.husky701.dash/files/rides
//
// Lines:
//   # comments: app config at the start (fuel model k, idle load, tank size ...)
//   S,...  a sample every SAMPLE_MS (columns in the header line)
//   F,...  a fill-up as entered on the fuel page (full/partial, litres, estimate before)
// Fill-ups are logged even outside a ride, into the most recent ride file, so the fuel
// model can be fitted against what was really pumped.
package com.husky701.dash

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class RideLog(
    private val dir: File,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val wallClock: () -> LocalDateTime = { LocalDateTime.now() },
) {
    private var out: BufferedWriter? = null
    private var lastFile: File? = null
    private var startMs = 0L
    private var lastSampleMs = 0L
    private var lastFlushMs = 0L
    private var lastActiveMs = 0L
    var recording = false; private set
    val fileName: String get() = lastFile?.name ?: ""

    /** Call every tick with the latest data. [live] false (SIM) never records. */
    fun update(live: Boolean, canOk: Boolean, t: Telemetry, gps: GpsSample, d: Dash) {
        val now = clock()
        val active = live && canOk && t.rpm > 0
        if (active) lastActiveMs = now

        if (!recording) {
            if (active) start(d)
            else return
        }
        if (!recording) return

        if (now - lastActiveMs > RIDE_END_IDLE_MS) { stop(); return }

        if (now - lastSampleMs >= SAMPLE_MS) {
            lastSampleMs = now
            val f = d.fuel
            val sb = StringBuilder(160)
            sb.append("S,").append(now - startMs)
            sb.append(',').append(wallClock().format(TIME))
            sb.append(',').append(if (canOk) t.rpm else "")
            sb.append(',').append(if (!canOk || t.gear == Telem.GEAR_UNKNOWN) "" else t.gear)
            sb.append(',').append(if (canOk) t.throttle else "")
            sb.append(',').append(t.coolantC?.let { fmt1(it) } ?: "")
            sb.append(',').append(if (t.vbatMv != Telem.VBAT_UNKNOWN) t.vbatMv else "")
            sb.append(',').append(t.wheelFront).append(',').append(t.wheelRear)
            sb.append(',').append(fmt1(d.speedKmh))
            sb.append(',').append(t.flags)
            sb.append(',').append(if (gps.fix) gps.latE7 / 1e7 else "")
            sb.append(',').append(if (gps.fix) gps.lonE7 / 1e7 else "")
            sb.append(',').append(if (gps.fix) fmt1(gps.kmh) else "")
            sb.append(',').append(if (gps.fix) gps.altM else "")
            sb.append(',').append(gps.source)
            sb.append(',').append(fmt3(f.litres))
            sb.append(',').append(fmt2(f.rateLph))
            sb.append(',').append(fmt3(f.totalUsed))
            sb.append(',').append(if (d.logic.fanOn) 1 else 0)
            sb.append(',').append(d.logic.coolant.name)
            sb.append(',').append(if (d.logic.shiftDown) 1 else 0)
            sb.append(',').append(if (d.logic.shiftUp) 1 else 0)
            write(sb.toString())
        }
        if (now - lastFlushMs >= FLUSH_MS) {
            lastFlushMs = now
            runCatching { out?.flush() }
        }
    }

    /** A fill-up was saved on the fuel page. [estBefore] is the model's litres just before it. */
    fun fillUp(full: Boolean, litres: Float, estBefore: Float, usedSinceFill: Float, kmSinceFill: Float, k: Float) {
        val line = "F,${if (recording) clock() - startMs else ""},${wallClock().format(TIME)}," +
            "${if (full) "full" else "partial"},${fmt2(litres)},${fmt3(estBefore)},${fmt3(usedSinceFill)},${fmt1(kmSinceFill)},${k}"
        if (recording) { write(line); runCatching { out?.flush() }; return }
        // between rides: append to the latest file (or a small fill-up file if there is none)
        val f = lastFile ?: latestFile() ?: File(dir.apply { mkdirs() }, "fills.csv")
        runCatching { FileWriter(f, true).use { it.write(line + "\n") } }
    }

    fun stop() {
        if (!recording) return
        runCatching { out?.write("# end ${wallClock().format(TIME)}\n"); out?.close() }
        out = null
        recording = false
    }

    private fun start(d: Dash) {
        runCatching {
            dir.mkdirs()
            prune()
            val f = File(dir, "ride_${wallClock().format(FILE)}.csv")
            out = BufferedWriter(FileWriter(f, true), 16 * 1024)
            lastFile = f
            startMs = clock()
            lastSampleMs = 0L
            lastFlushMs = startMs
            recording = true
            write("# husky701 dash ride log v1")
            write("# start ${wallClock().format(TIME)}")
            write("# fuel k=${d.fuel.kNow} idle_load=${DashConfig.FUEL_IDLE_LOAD} tank_l=${DashConfig.FUEL_TANK_L}" +
                " cut_rpm=${DashConfig.FUEL_CUT_RPM} cut_thr=${DashConfig.FUEL_CUT_THROTTLE} learned_fills=${d.fuel.learnedFills}" +
                " litres=${fmt3(d.fuel.litres)} used_since_fill=${fmt3(d.fuel.usedSinceFill)} km_since_fill=${fmt1(d.fuel.kmSinceFill)}")
            write("# wheel_kmh_per_count=${DashConfig.WHEEL_KMH_PER_COUNT} speed_from_front=${DashConfig.SPEED_FROM_FRONT}")
            write("type,ms,time,rpm,gear,throttle,coolant_c,vbat_mv,wheel_f,wheel_r,speed_kmh,flags," +
                "lat,lon,gps_kmh,alt_m,gps_src,fuel_l,fuel_lph,fuel_used_l,fan,coolant_level,lug,shift")
        }.onFailure { recording = false; out = null }
    }

    private fun write(s: String) {
        val w = out ?: return
        runCatching { w.write(s); w.write("\n") }.onFailure { stop() }
    }

    private fun latestFile(): File? =
        dir.listFiles { f -> f.name.startsWith("ride_") }?.maxByOrNull { it.name }

    /** Keeps the newest rides within MAX_TOTAL_BYTES. */
    private fun prune() {
        val files = dir.listFiles { f -> f.name.startsWith("ride_") }?.sortedByDescending { it.name } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > MAX_TOTAL_BYTES) f.delete()
        }
    }

    private fun fmt1(v: Float) = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Float) = String.format(Locale.US, "%.2f", v)
    private fun fmt3(v: Float) = String.format(Locale.US, "%.3f", v)

    companion object {
        const val SAMPLE_MS = 100L                  // 10 samples a second (~3.5 MB per hour)
        const val FLUSH_MS = 2000L                  // at most 2 s lost if the phone loses power
        const val RIDE_END_IDLE_MS = 120_000L       // 2 min with no CAN data or engine off ends the ride
        const val MAX_TOTAL_BYTES = 500L * 1024 * 1024
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S")
        private val FILE = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
