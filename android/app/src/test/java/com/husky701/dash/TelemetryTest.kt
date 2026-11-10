package com.husky701.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTest {
    private var now = 1000L
    private val clock = { now }

    private val sample = Telemetry(
        seq = 7, rpm = 7512, gear = 3, throttle = 200, coolantDc = -125, wheelFront = 1700,
        wheelRear = 1720, vbatMv = 14210, flags = Telem.FLAG_CAN_OK or Telem.FLAG_MAP2,
        clockHour = 13, clockMin = 45, latE7 = -338990000, lonE7 = 1512340000, speedDkmh = 1015,
        courseDd = 3599, sats = 9, fix = 1, day = 9806, altM = -12, hdopX10 = 12,
    )

    @Test fun crcMatchesCcittFalseCheckValue() {
        assertEquals(0x29B1, Telem.crc16("123456789".toByteArray()))
    }

    @Test fun payloadIs38Bytes() {
        assertEquals(38, sample.encodePayload().size)
        assertEquals(42, sample.encodeFrame().size)
    }

    @Test fun layoutMatchesTelemetryPacketH() {
        val p = sample.encodePayload()
        assertEquals(Telem.VERSION, p[0].toInt())
        assertEquals(7512 and 0xFF, p[2].toInt() and 0xFF)        // rpm, little-endian
        assertEquals(7512 shr 8, p[3].toInt() and 0xFF)
        assertEquals(3, p[4].toInt())                             // gear
        assertEquals(13, p[16].toInt())                           // clock_hour
        assertEquals(9, p[30].toInt())                            // gps_sats
        assertEquals(12, p[36].toInt())                           // gps_hdop_dx10
    }

    @Test fun roundTrip() {
        val parser = FrameParser(clock)
        val got = parser.feed(sample.encodeFrame())
        assertEquals(sample, got)
        assertEquals(-12.5f, got!!.coolantC!!, 0.001f)
        assertTrue(parser.alive())
    }

    @Test fun badCrcIsCountedAndRejected() {
        val parser = FrameParser(clock)
        val f = sample.encodeFrame()
        f[10] = (f[10].toInt() xor 1).toByte()
        assertNull(parser.feed(f))
        assertEquals(1, parser.stats.crcErrors)
    }

    @Test fun resyncsAfterGarbageAndCountsDrops() {
        val parser = FrameParser(clock)
        val bytes = byteArrayOf(0x00, 0xA5.toByte(), 0x11) + sample.copy(seq = 1).encodeFrame() +
            sample.copy(seq = 4).encodeFrame()
        assertNotNull(parser.feed(bytes))
        assertEquals(2, parser.stats.framesOk)
        assertEquals(2, parser.stats.seqDrops)
    }

    @Test fun bleEverySecondFrameIsNotADrop() {
        val parser = FrameParser(clock).apply { seqStep = DashConfig.BLE_SEQ_STEP }
        parser.feed(sample.copy(seq = 10).encodeFrame() + sample.copy(seq = 12).encodeFrame() +
            sample.copy(seq = 16).encodeFrame())
        assertEquals(3, parser.stats.framesOk)
        assertEquals(2, parser.stats.seqDrops)      // 14 missing = 1 BLE frame = 2 seq numbers
    }

    @Test fun linkTimesOut() {
        val parser = FrameParser(clock)
        parser.feed(sample.encodeFrame())
        now += DashConfig.LINK_TIMEOUT_MS + 1
        assertTrue(!parser.alive())
    }

    @Test fun simulatorFramesParse() {
        val parser = FrameParser(clock)
        val sim = Simulator(clock)
        repeat(3000) { now += 20; sim.update(parser) }      // one minute of ride
        assertTrue(parser.stats.framesOk > 2900)
        assertTrue(parser.stats.crcErrors > 0)              // fault injection is on
        assertTrue(parser.stats.seqDrops > 0)
    }

    @Test fun tapGestureEndsAfterGap() {
        val taps = TapGesture(clock)
        taps.onTouchDown(); now += 100; taps.onTouchDown()
        now += 100
        assertEquals(0, taps.poll())
        now += DashConfig.TAP_GAP_MS
        assertEquals(2, taps.poll())
    }
}
