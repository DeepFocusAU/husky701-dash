// Telemetry.kt - the wire format from the CAN node, mirroring include/telemetry_packet.h.
//
// Framing: [0xA5][0x5A][payload, 38 bytes][crc16 lo][crc16 hi]
// CRC is CRC-16/CCITT-FALSE over the payload only. All fields little-endian.
// If telemetry_packet.h changes, change this file to match (and TelemetryTest).
package com.husky701.dash

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Telem {
    const val SYNC0 = 0xA5
    const val SYNC1 = 0x5A
    const val VERSION = 5
    const val PAYLOAD_LEN = 38
    const val FRAME_LEN = 2 + PAYLOAD_LEN + 2

    const val GEAR_UNKNOWN = 0xFF
    const val COOLANT_UNKNOWN = -32768
    const val VBAT_UNKNOWN = 0
    const val CLOCK_UNKNOWN = 0xFF
    /** reserved bit: the CAN node is sending made-up test data. */
    const val RES_NODE_SIM = 0x01
    /** Where the reserved byte sits in a whole wire frame (2 sync bytes, then the payload). */
    const val FRAME_RESERVED_AT = 2 + 15

    const val FLAG_CLUTCH_IN = 1 shl 0
    const val FLAG_KILL_RUN = 1 shl 1
    const val FLAG_STAND_UP = 1 shl 2
    const val FLAG_MAP2 = 1 shl 3
    const val FLAG_THROTTLE_OPEN = 1 shl 4
    const val FLAG_CAN_OK = 1 shl 7

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF). */
    fun crc16(data: ByteArray, off: Int = 0, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in off until off + len) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }
}

data class Telemetry(
    val version: Int = Telem.VERSION,
    val seq: Int = 0,
    val rpm: Int = 0,
    val gear: Int = Telem.GEAR_UNKNOWN,
    val throttle: Int = 0,             // 0..255 raw
    val coolantDc: Int = Telem.COOLANT_UNKNOWN,
    val wheelFront: Int = 0,
    val wheelRear: Int = 0,
    val vbatMv: Int = Telem.VBAT_UNKNOWN,
    val flags: Int = 0,
    val reserved: Int = 0,
    val clockHour: Int = Telem.CLOCK_UNKNOWN,
    val clockMin: Int = 0,
    val latE7: Int = 0,
    val lonE7: Int = 0,
    val speedDkmh: Int = 0,
    val courseDd: Int = 0,
    val sats: Int = 0,
    val fix: Int = 0,
    val day: Int = 0,
    val altM: Int = 0,
    val hdopX10: Int = 0,
) {
    fun has(flag: Int) = flags and flag != 0
    val coolantC: Float? get() = if (coolantDc == Telem.COOLANT_UNKNOWN) null else coolantDc / 10f
    val vbatV: Float? get() = if (vbatMv == Telem.VBAT_UNKNOWN) null else vbatMv / 1000f
    val gpsFix: Boolean get() = fix == 1

    fun encodePayload(): ByteArray {
        val b = ByteBuffer.allocate(Telem.PAYLOAD_LEN).order(ByteOrder.LITTLE_ENDIAN)
        b.put(version.toByte()).put(seq.toByte())
        b.putShort(rpm.toShort()).put(gear.toByte()).put(throttle.toByte())
        b.putShort(coolantDc.toShort()).putShort(wheelFront.toShort()).putShort(wheelRear.toShort())
        b.putShort(vbatMv.toShort()).put(flags.toByte()).put(reserved.toByte())
        b.put(clockHour.toByte()).put(clockMin.toByte())
        b.putInt(latE7).putInt(lonE7)
        b.putShort(speedDkmh.toShort()).putShort(courseDd.toShort())
        b.put(sats.toByte()).put(fix.toByte()).putShort(day.toShort()).putShort(altM.toShort())
        b.put(hdopX10.toByte()).put(0)
        return b.array()
    }

    fun encodeFrame(): ByteArray {
        val p = encodePayload()
        val crc = Telem.crc16(p)
        return byteArrayOf(Telem.SYNC0.toByte(), Telem.SYNC1.toByte()) + p +
            byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
    }

    companion object {
        fun decodePayload(buf: ByteArray, off: Int = 0): Telemetry {
            val b = ByteBuffer.wrap(buf, off, Telem.PAYLOAD_LEN).order(ByteOrder.LITTLE_ENDIAN)
            fun u8() = b.get().toInt() and 0xFF
            fun u16() = b.short.toInt() and 0xFFFF
            fun s16() = b.short.toInt()
            return Telemetry(
                version = u8(), seq = u8(), rpm = u16(), gear = u8(), throttle = u8(),
                coolantDc = s16(), wheelFront = u16(), wheelRear = u16(), vbatMv = u16(),
                flags = u8(), reserved = u8(), clockHour = u8(), clockMin = u8(),
                latE7 = b.int, lonE7 = b.int, speedDkmh = u16(), courseDd = u16(),
                sats = u8(), fix = u8(), day = u16(), altM = s16(), hdopX10 = u8(),
            )
        }
    }
}

class LinkStats {
    var framesOk = 0L
    var crcErrors = 0L
    var seqDrops = 0L
    var lastFrameMs = 0L
    var framesPerSec = 0f
}

/**
 * Byte-at-a-time frame parser, same state machine as the ESP32 dash's TelemetryLink.
 * BLE notifications carry whole frames, but feeding bytes keeps one code path for
 * the simulator, BLE, and any partial or joined notifications.
 */
class FrameParser(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private enum class State { SYNC0, SYNC1, BODY }

    private var state = State.SYNC0
    private val buf = ByteArray(Telem.PAYLOAD_LEN + 2)
    private var idx = 0
    private var haveSeq = false
    private var lastSeq = 0
    private var rateStart = 0L
    private var rateCount = 0

    val stats = LinkStats()

    /** Expected seq increase per frame: 1 on RS-485 and the simulator, BLE_FRAME_DIV over BLE. */
    var seqStep = 1

    var latest = Telemetry()
        private set

    /** Returns the new payload when this byte completes a valid frame. */
    fun feed(byte: Byte): Telemetry? {
        val b = byte.toInt() and 0xFF
        when (state) {
            State.SYNC0 -> if (b == Telem.SYNC0) state = State.SYNC1
            State.SYNC1 -> state = when (b) {
                Telem.SYNC1 -> { idx = 0; State.BODY }
                Telem.SYNC0 -> State.SYNC1
                else -> State.SYNC0
            }
            State.BODY -> {
                buf[idx++] = byte
                if (idx < buf.size) return null
                state = State.SYNC0
                val rxCrc = (buf[Telem.PAYLOAD_LEN].toInt() and 0xFF) or
                    ((buf[Telem.PAYLOAD_LEN + 1].toInt() and 0xFF) shl 8)
                if (rxCrc == Telem.crc16(buf, 0, Telem.PAYLOAD_LEN) &&
                    (buf[0].toInt() and 0xFF) == Telem.VERSION
                ) {
                    return accept(Telemetry.decodePayload(buf))
                }
                stats.crcErrors++
            }
        }
        return null
    }

    fun feed(bytes: ByteArray): Telemetry? {
        var last: Telemetry? = null
        for (b in bytes) feed(b)?.let { last = it }
        return last
    }

    private fun accept(t: Telemetry): Telemetry {
        if (haveSeq) {
            val gap = (t.seq - lastSeq - seqStep) and 0xFF
            if (gap in 1..127) stats.seqDrops += gap
        }
        haveSeq = true
        lastSeq = t.seq
        latest = t
        stats.framesOk++
        stats.lastFrameMs = clock()
        rateCount++
        return t
    }

    /** Call regularly to keep framesPerSec current. */
    fun updateRate() {
        val now = clock()
        if (rateStart == 0L) rateStart = now
        val span = now - rateStart
        if (span >= 1000) {
            stats.framesPerSec = rateCount * 1000f / span
            rateCount = 0
            rateStart = now
        }
    }

    fun alive(timeoutMs: Long = DashConfig.LINK_TIMEOUT_MS): Boolean =
        stats.lastFrameMs != 0L && clock() - stats.lastFrameMs < timeoutMs

    fun reset() {
        state = State.SYNC0
        haveSeq = false
        latest = Telemetry()
        stats.framesOk = 0; stats.crcErrors = 0; stats.seqDrops = 0
        stats.lastFrameMs = 0; stats.framesPerSec = 0f
        rateCount = 0; rateStart = 0
    }
}
