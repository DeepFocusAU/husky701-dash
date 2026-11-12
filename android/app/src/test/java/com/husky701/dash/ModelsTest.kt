package com.husky701.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ModelsTest {
    private var now = 1_000L
    private val clock = { now }

    private fun run(seconds: Int, step: Long = 100, each: () -> Unit) {
        repeat((seconds * 1000 / step).toInt()) { now += step; each() }
    }

    @Test fun fuelBurnsAndLearnsFromFullFill() {
        val store = Store(null)
        val fuel = FuelModel(store, clock)
        fuel.fillUp(true, 0f)                                  // reference full tank
        run(600) { fuel.update(true, 5000, 80, 100f) }         // 10 min at 100 km/h
        assertTrue(fuel.litres < DashConfig.FUEL_TANK_L)
        assertEquals(16.7f, fuel.kmSinceFill, 0.2f)
        val est = fuel.usedSinceFill
        fuel.fillUp(true, est * 1.2f + 3f)                     // pumped more than estimated
        assertEquals(1, fuel.learnedFills)
        assertTrue(fuel.kRatio > 1f)
        assertEquals(DashConfig.FUEL_TANK_L, fuel.litres, 0.001f)
        // survives a restart
        assertEquals(1, FuelModel(store, clock).learnedFills)
    }

    @Test fun noFuelOnOverrun() {
        val fuel = FuelModel(Store(null), clock)
        val before = fuel.litres
        run(60) { fuel.update(true, 4000, 0, 60f) }
        assertEquals(before, fuel.litres, 0.0001f)
    }

    @Test fun simRideUsesSensibleFuel() {
        // the SIM ride should look like real riding on the Fuel page, not a drag race
        val parser = FrameParser(clock)
        val sim = Simulator(clock)
        val fuel = FuelModel(Store(null), clock)
        repeat(10 * DashConfig.SIM_LOOP_MS.toInt() / 20) {
            now += 20
            sim.update(parser)
            val t = parser.latest
            fuel.update(true, t.rpm, t.throttle, t.speedDkmh / 10f)
        }
        val l100 = fuel.usedSinceFill / fuel.kmSinceFill * 100f
        println("SIM ride: ${fuel.kmSinceFill} km, ${fuel.usedSinceFill} L, $l100 L/100")
        assertTrue("SIM ride uses $l100 L/100", l100 in 3f..12f)        // about 9.4: trail riding
    }

    @Test fun fuelWarnings() {
        val fuel = FuelModel(Store(null), clock)
        assertEquals(FuelModel.Warn.NONE, fuel.warn)            // full tank
        fuel.editOpen(0f); fuel.editToggleType()                 // PARTIAL fill to set the level
        fun setLitres(l: Float) {
            val f = FuelModel::class.java.getDeclaredField("litres"); f.isAccessible = true; f.setFloat(fuel, l)
        }
        setLitres(DashConfig.FUEL_TANK_L * 0.11f); assertTrue(fuel.warn != FuelModel.Warn.RED && fuel.warn != FuelModel.Warn.FLASH)
        setLitres(DashConfig.FUEL_TANK_L * 0.09f); assertEquals(FuelModel.Warn.RED, fuel.warn)
        setLitres(DashConfig.FUEL_TANK_L * 0.04f); assertEquals(FuelModel.Warn.FLASH, fuel.warn)
    }

    @Test fun fillEditorOpensWhileMoving() {
        val fuel = FuelModel(Store(null), clock)
        fuel.editOpen(30f)
        assertTrue(fuel.editing)
        assertFalse(fuel.editBlocked)
        fuel.editToggleType()                                  // PARTIAL
        fuel.editNext()
        fuel.editStepLitres(-100f); fuel.editStepLitres(2f)
        assertTrue(fuel.editNext())
        assertFalse(fuel.editing)
        // PARTIAL with a minus: fuel taken out
        val before = fuel.litres
        fuel.editOpen(0f); fuel.editToggleType(); fuel.editNext()
        fuel.editStepLitres(-100f)                              // stops at what's in the tank
        assertEquals(-before, fuel.editLitres, 0.001f)
        fuel.editStepLitres(before - 1f)                        // -1 L
        assertEquals(-1f, fuel.editLitres, 0.001f)
        assertTrue(fuel.editNext())
        assertEquals(before - 1f, fuel.litres, 0.001f)
        fuel.editOpen(0f); fuel.editStepLitres(-100f)          // FULL TANK never goes below 0
        assertEquals(0f, fuel.editLitres, 0.001f)
    }

    @Test fun simDataIsNotSaved() {
        val store = Store(null)
        store.persist = false
        val fuel = FuelModel(store, clock)
        fuel.fillUp(true, 10f)
        assertEquals(0, store.int("fuel.nLearn", 0))
        assertFalse(store.bool("fuel.fullRef", false))
    }

    @Test fun tripTodayClearsOnNewDay() {
        val trip = TripModel(Store(null), clock)
        run(60) { trip.update(true, 60f, 0f, 20000) }
        assertEquals(1f, trip.today.km, 0.05f)
        trip.update(true, 0f, 0f, 20001)
        assertEquals(0f, trip.today.km, 0.001f)
        assertEquals(1f, trip.sinceRefuel.km, 0.05f)
    }

    @Test fun batteryHealthFromCrank() {
        val b = BatteryMonitor(Store(null), clock)
        run(2) { b.update(true, 12.6f, 0) }
        run(1) { b.update(true, 9.2f, 300) }                   // cranking sag
        run(3) { b.update(true, 14.1f, 1500) }                 // running
        assertTrue(b.takeNewResult())
        assertEquals(BattHealth.WEAK, b.health)
        assertEquals(9.2f, b.lastMin, 0.01f)
    }

    private fun gpsAt(latE7: Int, lonE7: Int, kmh: Float = 30f, course: Float = 0f) =
        GpsSample(fix = true, latE7 = latE7, lonE7 = lonE7, kmh = kmh, courseDeg = course, courseValid = true, sats = 10, source = "phone")

    @Test fun trailWaypointsAndNavigation() {
        val nav = NavModel(Store(null), clock)
        var lat = -338990000
        val lon = 1512340000
        // ride 1 km north, dropping a waypoint at the start and halfway
        for (i in 0..100) {
            now += 250
            nav.update(gpsAt(lat, lon))
            if (i == 0 || i == 50) nav.dropWaypoint()
            lat += 900                                          // ~10 m
        }
        assertEquals(2, nav.waypoints.size)
        assertTrue(nav.trail.size in 40..60)                    // one point per 20 m
        // navigate back to W1: leads through W2 first
        nav.menuOpen()
        assertEquals("TracBack: retrace track", nav.menuLabel(0))
        nav.menuNext(); nav.menuNext()                          // W1 (newest first: W2, W1)
        assertTrue(nav.menuLabel(nav.menuIndex).startsWith("W1"))
        nav.menuChoose()
        assertTrue(nav.navigating)
        assertEquals(2, nav.targetId)
        assertEquals(1, nav.finalId)
        assertEquals(180f, nav.targetBearingDeg, 2f)
    }

    @Test fun waypointNeedsFix() {
        val nav = NavModel(Store(null), clock)
        nav.dropWaypoint()
        assertEquals("NO GPS FIX", nav.toast)
        assertEquals(0, nav.waypoints.size)
    }

    @Test fun tpmsDecodersAndPairing() {
        // FOBO manufacturer data: last 4 bytes [.., temp+50, raw hi, raw lo], raw = batt code << 10 | kPa/10
        val raw = (30 shl 10) or 230                           // 2.30 bar, 3.0 V
        val fobo = TpmsDecoders.manufacturer(295, byteArrayOf(1, 2, (25 + 50).toByte(), (raw shr 8).toByte(), raw.toByte()), false)!!
        assertEquals("FOBO", fobo.type)
        assertEquals(2.30f, fobo.bar, 0.001f)
        assertEquals(25, fobo.tempC)
        assertEquals(3.0f, fobo.battV, 0.001f)
        assertNull(TpmsDecoders.manufacturer(76, byteArrayOf(1, 2, 3, 4), false))   // an Apple device

        val tpms = TpmsModel(Store(null), clock)
        tpms.onSensor("AA:BB:CC:DD:00:01", fobo, -50)
        tpms.onSensor("AA:BB:CC:DD:00:02", fobo.copy(bar = 1.2f), -70)
        tpms.pairOpen()
        tpms.pairAssign()                                       // strongest -> FRONT
        tpms.pairNext(); tpms.pairAssign()                      // next -> REAR
        assertFalse(tpms.pairing)
        assertEquals("00:01", tpms.front.tag)
        assertEquals(1.2f, tpms.rear.bar, 0.001f)
    }

    // ------------------------------------------------------------ Dash: taps and warnings
    private class FakePlatform : Dash.Platform {
        var level = -1f
        var maintenance = false
        override fun setBrightness(level: Float) { this.level = level }
        override fun exitForMaintenance() { maintenance = true }
        override val applianceText = "test"
        override var sourceName = "SIM"
        override var brightnessIndex = 2
        override var showThrottle = true
        override var mapAppName = "GOOGLE"
        var split: Dash.MapApp? = null
        override fun openSplit(app: Dash.MapApp): String? { split = app; return null }
        override fun closeSplit() { split = null }
        var beeps = 0
        override fun beep() { beeps++ }
        override var wifiSsid = ""
        override var wifiPass = ""
    }

    // ------------------------------------------------------------ CAN node update mode
    @Test fun nodeOtaPayloadMatchesNodeFormat() {
        val p = NodeOta.startPayload("Note10", "secret123")!!
        assertEquals(1, p[0].toInt())
        assertEquals(6, p[1].toInt())
        assertEquals("Note10", String(p, 2, 6, Charsets.UTF_8))
        assertEquals(9, p[8].toInt())
        assertEquals("secret123", String(p, 9, 9, Charsets.UTF_8))
        assertEquals(18, p.size)
        // open network: empty password is allowed
        assertEquals(9, NodeOta.startPayload("Note10", "")!!.size)
        assertEquals(listOf<Byte>(0), NodeOta.stopPayload.toList())
    }

    @Test fun nodeOtaRejectsBadWifiDetails() {
        assertEquals("NO WI-FI NAME", NodeOta.problem("", "secret123"))
        assertEquals("PASSWORD TOO SHORT", NodeOta.problem("Note10", "short"))
        assertEquals("WI-FI NAME TOO LONG", NodeOta.problem("x".repeat(33), "secret123"))
        assertEquals("PASSWORD TOO LONG", NodeOta.problem("Note10", "x".repeat(64)))
        assertNull(NodeOta.startPayload("Note10", "short"))
        assertTrue(NodeOta.active("ready 192.168.43.57"))
        assertTrue(NodeOta.active("updating 40%"))
        assertFalse(NodeOta.active("off: no upload within 10 min"))
        assertFalse(NodeOta.active(null))
        assertEquals("ready 192.168.1.50", NodeOta.statusText("ready 192.168.1.50".toByteArray()))
        assertNull(NodeOta.statusText("received".toByteArray()))
        assertEquals("unreadable (update the node by USB)", NodeOta.statusText(NodeOta.startPayload("WifiOne", "secret123")!!))
    }

    private fun makeDash(hour: Int = 12, platform: FakePlatform = FakePlatform()) =
        Dash(FrameParser(clock), Simulator(clock), null, null, Store(null), platform, clock,
            { LocalDateTime.of(2026, 11, 8, hour, 0) })

    @Test fun pagesCycleWithoutClock() {
        val d = makeDash()
        val seen = mutableListOf<Dash.Page>()
        repeat(Dash.Page.entries.size) { seen.add(d.page); d.onTaps(1) }
        assertEquals(Dash.Page.MAIN, d.page)
        assertEquals(listOf("MAIN", "FUEL", "TRIP", "TYRES", "GPS", "DIAG"), seen.map { it.name })
    }

    @Test fun splitScreenTaps() {
        val p = FakePlatform()
        val d = makeDash(platform = p)
        d.onTaps(4)                                             // Main: open split with Google Maps
        assertEquals(Dash.MapApp.GOOGLE, p.split)
        d.compact = true
        d.onTaps(1)                                             // no page change in the strip
        assertEquals(Dash.Page.MAIN, d.page)
        assertTrue(d.compactSwap)
        d.onTaps(2)                                             // switch to Gaia, remembered
        assertEquals(Dash.MapApp.GAIA, p.split)
        assertEquals("GAIA", p.mapAppName)
        d.onTaps(4)
        assertNull(p.split)
    }

    /** Live source, frames fed straight into the parser (no BLE). */
    private fun liveDash(p: FakePlatform) = makeDash(platform = p.apply { sourceName = "LIVE" })

    private fun feed(d: Dash, flags: Int, rpm: Int = 0, seq: Int) =
        d.parser.feed(Telemetry(seq = (seq * DashConfig.BLE_SEQ_STEP) and 0xFF, rpm = rpm, flags = flags, vbatMv = 12600).encodeFrame())

    @Test fun keyOnBeepsTwiceThenEvery30s() {
        val p = FakePlatform()
        val d = liveDash(p)
        var i = 0
        val canOk = Telem.FLAG_CAN_OK or Telem.FLAG_KILL_RUN
        repeat(((DashConfig.KEYON_WARN_S + 1) * 25).toInt()) { now += 40; feed(d, canOk, seq = i++); d.tick() }
        assertTrue(d.canOk)
        assertEquals(Dash.AL_KEYON, d.alert?.id)
        assertEquals(1, p.beeps)                                // one double-beep when it shows
        repeat(29 * 25) { now += 40; feed(d, canOk, seq = i++); d.tick() }
        assertEquals(1, p.beeps)
        repeat(2 * 25) { now += 40; feed(d, canOk, seq = i++); d.tick() }
        assertEquals(2, p.beeps)                                // again 30 s later
        feed(d, canOk, rpm = 1300, seq = i++); now += 40; d.tick()  // engine started
        assertNull(d.alert)
    }

    @Test fun noCanDataMeansNoKeyOnWarning() {
        val p = FakePlatform()
        val d = liveDash(p)
        var i = 0
        repeat(((DashConfig.KEYON_WARN_S + 10) * 25).toInt()) { now += 40; feed(d, Telem.FLAG_KILL_RUN, seq = i++); d.tick() }
        assertTrue(d.alive)
        assertFalse(d.canOk)                                    // link up, bike bus silent: greyed out
        assertNull(d.alert)
        assertEquals(0, p.beeps)
    }

    @Test fun swipeChangesPageUnlessSheetOpen() {
        val d = makeDash()
        d.swipe(1); assertEquals(Dash.Page.FUEL, d.page)
        d.swipe(-1); d.swipe(-1); assertEquals(Dash.Page.DIAG, d.page)
        d.swipe(1)
        d.swipe(1)
        d.openFillUp()                                          // stopped: editor opens
        assertTrue(d.fuel.editing)
        d.swipe(1); assertEquals(Dash.Page.FUEL, d.page)        // sheet open: no page change
        d.fuel.editSetFull(false)
        d.fuel.editStepLitres(5f)
        d.saveFillUp()
        assertFalse(d.fuel.editing)
        d.compact = true
        d.swipe(1); assertEquals(Dash.Page.FUEL, d.page)        // strip: no pages
    }

    @Test fun waypointTouchAndZoom() {
        val store = Store(null)
        val nav = NavModel(store, clock)
        var lat = -338990000
        for (i in 0..60) { now += 250; nav.update(gpsAt(lat, 1512340000)); if (i == 0) nav.dropWaypoint(); lat += 900 }
        nav.menuOpen()
        nav.menuSelect(1)                                       // a tap selects W1...
        assertFalse(nav.navigating)
        assertEquals(1, nav.selectedId)
        nav.renameWaypoint(1, "  Camp; gate,2 ")              // ...a name (storage characters dropped)...
        assertEquals("Camp gate 2", nav.waypoints[0].name)
        assertEquals("Camp gate 2", NavModel(store, clock).waypoints[0].name)   // kept across restarts
        nav.menuCancel()
        nav.menuOpenAt(1)                                       // tap on the map marker: sheet with W1 picked
        assertTrue(nav.menuOpen)
        assertEquals(1, nav.selectedId)
        nav.navigateSelected()                                  // ...then NAVIGATE
        assertTrue(nav.navigating)
        assertTrue(nav.menuLabel(1).endsWith("◀ NAV") || !nav.menuOpen)
        nav.menuOpen()
        val clear = nav.menuCount - 1
        nav.menuSelect(clear)
        assertEquals("Tap again: clear all", nav.menuLabel(clear))
        nav.menuSelect(clear)
        assertEquals(0, nav.waypoints.size)
        val r = nav.mapRadiusM
        nav.zoom(1)
        assertFalse(nav.autoZoom)
        assertTrue(nav.mapRadiusM > r)
        nav.zoomAuto()
        assertTrue(nav.autoZoom)
        nav.zoomScale(0.25f)                                    // pinch in: wider
        val before = nav.mapRadiusM
        nav.zoomScale(2f)                                       // pinch out: closer in
        assertEquals(before / 2f, nav.mapRadiusM, 1f)
        assertFalse(nav.autoZoom)
        nav.zoomScale(0.0001f)
        assertEquals(50000f, nav.mapRadiusM, 0.1f)              // held to the widest scale
    }

    @Test fun what3wordsOnlyLooksUpWhenAsked() {
        var calls = 0
        var day = 100L
        val store = Store(null)
        val w = What3Words("KEY", store, clock, { day },
            fetch = { calls++; "{\"words\":\"filled.count.soap\"}" }, async = false)
        val here = NavPoint(-338990000, 1512340000)
        assertEquals("tap logo to look up", w.status)
        // recall alone never calls the API, however long you sit there
        repeat(300) { now += 1000; w.recall(here) }
        assertEquals(0, calls)
        assertNull(w.words)
        // the tap does
        w.lookup(here)
        assertEquals(1, calls)
        assertEquals("filled.count.soap", w.words)
        // a few metres away the old answer stays (grey on screen), and nothing is looked up
        val near = NavPoint(-338990000 + 900, 1512340000)        // ~10 m
        repeat(120) { now += 1000; w.recall(near) }
        assertEquals(1, calls)
        // back at a known spot: recalled for free, and a tap there costs nothing either
        assertTrue(w.recall(here))
        w.lookup(here)
        assertEquals(1, calls)
        // daily cap
        var lat = -338990000
        repeat(40) { lat += 5000; w.lookup(NavPoint(lat, 1512340000)) }
        assertEquals(DashConfig.W3W_DAILY_CAP, calls)
        assertEquals("daily limit reached", w.status)
        day++                                                    // next day: allowed again
        lat += 5000; w.lookup(NavPoint(lat, 1512340000))
        assertEquals(DashConfig.W3W_DAILY_CAP + 1, calls)
        // restarted in the same place: the last answer comes back, no lookup
        val again = What3Words("KEY", store, clock, { day }, fetch = { calls++; "{}" }, async = false)
        assertEquals("filled.count.soap", again.words)
        repeat(120) { now += 1000; again.recall(NavPoint(lat, 1512340000)) }
        assertEquals(DashConfig.W3W_DAILY_CAP + 1, calls)
    }

    @Test fun degreesDecimalMinutes() {
        assertEquals("S 33° 53.946′", NavModel.formatDM(-338991000, true))
        assertEquals("E 151° 14.035′", NavModel.formatDM(1512339170, false))
        assertEquals("N 0° 00.000′", NavModel.formatDM(0, true))
        assertEquals("W 2° 00.000′", NavModel.formatDM(-19999999, false))   // 59.99999′ rounds up into the next degree
    }

    @Test fun headingPrefersPhoneCompass() {
        val d = makeDash()
        assertNull(d.headingDeg)                                // stopped, no compass
        d.compassDeg = 93f
        assertEquals(93f, d.headingDeg!!, 0.01f)
    }

    @Test fun nightDimsAndManualOverrides() {
        val p = FakePlatform()
        val d = makeDash(hour = 22, platform = p)
        now += 20; d.tick()
        assertEquals(DashConfig.NIGHT_LEVEL, p.level, 0.001f)
        d.onTaps(3)                                             // manual brightness wins until day comes
        now += 20; d.tick()
        assertEquals(DashConfig.BRIGHTNESS_LEVELS[0], p.level, 0.001f)
    }

    @Test fun simRideRunsAllModels() {
        val d = makeDash()
        repeat(3500) { now += 20; d.tick() }                     // 70 s: one full scripted ride
        assertTrue(d.parser.stats.framesOk > 3000)
        assertTrue(d.fuel.usedSinceFill > 0f)
        assertTrue(d.trip.today.km > 0.2f)
        assertTrue(d.nav.trail.isNotEmpty())
        assertNotNull(d.nav.position)
    }
}
