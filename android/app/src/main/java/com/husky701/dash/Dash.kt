// Dash.kt - the dash's brain: data source, models, warnings, brightness and tap actions.
// DashView only draws what is here. Same behaviour as the ESP32 dash's main.cpp, except:
//   - position, speed and course come from the phone's GPS (the CAN node's is a fallback)
//   - time and date come from the phone's clock
//   - there is no Clock page (the time is in the header of every page)
package com.husky701.dash

import java.time.LocalDateTime

class Dash(
    val parser: FrameParser,
    val simulator: Simulator,
    val ble: BleTelemetry?,
    val phoneGps: PhoneGps?,
    private val store: Store,
    private val platform: Platform,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val localTime: () -> LocalDateTime = { LocalDateTime.now() },
) {
    interface Platform {
        fun setBrightness(level: Float)
        fun exitForMaintenance()
        val applianceText: String
        var sourceName: String
        var brightnessIndex: Int
        var mapAppName: String
        /** Main page shows the throttle bar (Diagnostics toggle, kept across restarts). */
        var showThrottle: Boolean
        /** Opens the map app above the tacho strip. Null if it worked, else a message to show. */
        fun openSplit(app: MapApp): String?
        /** Back to the full-screen dash. */
        fun closeSplit()
        /** Two short beeps (key on, engine off). */
        fun beep()
        /** The phone's Wi-Fi hotspot, sent to the CAN node for updates (kept across restarts). */
        var hotspotSsid: String
        var hotspotPass: String
    }

    /** The map app shown above the tacho in split screen. The first installed package is used. */
    enum class MapApp(val label: String, val packages: List<String>) {
        GOOGLE("GOOGLE MAPS", listOf("com.google.android.apps.maps")),
        GAIA("GAIA GPS", listOf("com.trailbehind.android.gaiagps.pro", "com.trailbehind.android.gaiagps")),
    }

    enum class Source(val label: String) { LIVE("LIVE (BLE)"), SIM("SIM") }
    enum class Page(val title: String) {
        MAIN("MAIN"), FUEL("FUEL"), TRIP("TRIP"), TYRES("TYRES"), GPS("MAP"), DIAG("DIAGNOSTICS")
    }

    class Alert(val id: Int, val bad: Boolean, val line1: String, val line2: String)

    val logic = DashLogic(clock)
    val fuel = FuelModel(store, clock)
    val trip = TripModel(store, clock)
    val battery = BatteryMonitor(store, clock)
    val nav = NavModel(store, clock)
    val tpms = TpmsModel(store, clock)

    var source: Source = runCatching { Source.valueOf(platform.sourceName) }.getOrDefault(Source.LIVE)
        private set
    var page = Page.MAIN
        private set
    var gps = GpsSample()
        private set
    var vbatShown = 0f
        private set
    var alert: Alert? = null
        private set
    val now: LocalDateTime get() = localTime()

    /** True while the dash is the strip under a map app. Set by the activity. */
    /** True heading from the phone's compass, or null (no sensor, or not running). */
    var compassDeg: Float? = null
    /** Heading for the compass rose and map pointer: the phone compass, else the GPS course when moving. */
    val headingDeg: Float? get() = compassDeg ?: if (nav.headingValid) nav.headingDeg else null

    /** what3words lookups; null when not set up. */
    var w3w: What3Words? = null

    var compact = false
    /** Split screen: 1 tap swaps coolant and battery out of turn. */
    var compactSwap = false
        private set
    var mapApp: MapApp = runCatching { MapApp.valueOf(platform.mapAppName) }.getOrDefault(MapApp.GOOGLE)
        private set

    private var toastText = ""
    private var toastUntil = 0L
    private var maintArmUntil = 0L
    private var lastModelMs = 0L
    private var keyOnSinceMs = 0L
    private var battAlertUntil = 0L
    private val snoozeUntil = LongArray(4)
    private val shownSince = LongArray(4)
    private var manualBright = false
    private var lastNight: Boolean? = null
    private var lastBrightness = -1f

    init { applySource() }

    val alive get() = parser.alive()
    /** Link up and the node is getting frames from the bike. Everything from CAN is greyed out otherwise. */
    val canOk get() = alive && parser.latest.has(Telem.FLAG_CAN_OK)
    /** Asks the rider to type something (set by MainActivity): title, starting text, then the answer (null = cancelled). */
    var askText: ((String, String, (String?) -> Unit) -> Unit)? = null

    /** A sheet (fill-up, pairing, waypoints) is open: swipes don't change page. */
    val overlayOpen get() = fuel.editing || tpms.pairing || nav.menuOpen
    val appliance get() = platform.applianceText
    val speedKmh get() = logic.speedKmh ?: 0f

    private var lastTickMs = -1L

    /** Call every screen frame. Safe to call from two views (dash and strip): extra calls are skipped. */
    fun tick() {
        val tickMs = clock()
        if (lastTickMs >= 0 && tickMs - lastTickMs < 5) return
        lastTickMs = tickMs
        val live = source == Source.LIVE
        parser.seqStep = if (live) DashConfig.BLE_SEQ_STEP else 1
        if (!live) simulator.update(parser)
        parser.updateRate()

        val t = parser.latest
        val nodeGps = GpsSample.fromTelemetry(t, alive, if (live) "node" else "sim")
        gps = if (!live) nodeGps else {
            val p = phoneGps?.sample()
            when {
                p != null && p.fix -> p
                nodeGps.fix -> nodeGps
                else -> p ?: nodeGps
            }
        }
        logic.update(t, alive, gps)

        val vbatOk = alive && t.vbatMv != Telem.VBAT_UNKNOWN
        vbatShown = if (!vbatOk) 0f else (t.vbatMv / 1000f).let { v -> if (vbatShown == 0f) v else vbatShown + (v - vbatShown) * 0.1f }

        val nowMs = clock()
        if (nowMs - lastModelMs >= 100) {
            lastModelMs = nowMs
            fuel.update(alive, t.rpm, t.throttle, speedKmh)
            trip.update(alive, speedKmh, fuel.totalUsed, localTime().toLocalDate().toEpochDay())
            battery.update(vbatOk, t.vbatMv / 1000f, t.rpm)
            if (battery.takeNewResult() && (battery.health == BattHealth.WEAK || battery.health == BattHealth.BAD)) {
                battAlertUntil = nowMs + 8000
            }
        }
        tpms.update()
        nav.update(gps)

        if (canOk && t.rpm == 0 && speedKmh < 1f) {
            if (keyOnSinceMs == 0L) keyOnSinceMs = nowMs
        } else keyOnSinceMs = 0L

        updateBrightness()
        pickAlert(t, nowMs)
        // no API calls here (only the logo tap does that): just show an answer we already have
        if (page == Page.GPS && !compact && nav.fix) w3w?.recall(nav.position)

        // key on, engine off: two beeps when the warning shows, again every 30 s while it stays up
        if (alert?.id == AL_KEYON) {
            if (nowMs >= nextBeepMs) {
                platform.beep()
                nextBeepMs = nowMs + DashConfig.KEYON_BEEP_REPEAT_MS
            }
        } else nextBeepMs = 0L
    }

    private var nextBeepMs = 0L

    // ------------------------------------------------------------ touch actions
    fun swipe(dir: Int) {
        if (overlayOpen || compact) return
        val n = Page.entries.size
        page = Page.entries[(page.ordinal + dir + n) % n]
    }

    fun dismissAlert() {
        val a = alert ?: return
        snoozeUntil[a.id] = clock() + DashConfig.ALERT_SNOOZE_MS
        if (a.id == AL_BATT) battAlertUntil = 0
        alert = null
    }

    fun openFillUp() = fuel.editOpen(speedKmh)

    fun saveFillUp() { if (fuel.editSave()) { trip.resetSinceRefuel(); showToast("FILL-UP SAVED") } }

    fun openSplit() { platform.openSplit(mapApp)?.let { showToast(it) } }

    fun closeSplit() = platform.closeSplit()

    fun swapMapApp() {
        val next = MapApp.entries[(mapApp.ordinal + 1) % MapApp.entries.size]
        val err = platform.openSplit(next)
        if (err == null) {
            mapApp = next
            platform.mapAppName = next.name
            showToast(next.label)
        } else showToast(err)
    }

    /** Diagnostics: LIVE (the CAN node over Bluetooth) or SIM (made-up ride, nothing saved). */
    fun setSource(s: Source) { if (s != source) toggleSource() }

    fun toggleSource() {
        source = if (source == Source.LIVE) Source.SIM else Source.LIVE
        platform.sourceName = source.name
        applySource()
        showToast("SOURCE: ${source.label}")
    }

    /** Twice within 5 s: unpin and open Settings. */
    fun maintenancePress() {
        val nowMs = clock()
        if (nowMs < maintArmUntil) { maintArmUntil = 0; platform.exitForMaintenance() }
        else { maintArmUntil = nowMs + 5000; showToast("TAP AGAIN TO EXIT") }
    }

    val maintenanceArmed get() = clock() < maintArmUntil
    val brightnessText get() = "${platform.brightnessIndex + 1}/${DashConfig.BRIGHTNESS_LEVELS.size}"

    // ------------------------------------------------------------ taps
    fun onTaps(nIn: Int) {
        var n = nIn
        // any gesture while a warning shows just dismisses it, for 5 minutes
        alert?.let {
            snoozeUntil[it.id] = clock() + DashConfig.ALERT_SNOOZE_MS
            if (it.id == AL_BATT) battAlertUntil = 0
            alert = null
            n = 0
        }
        if (n == 0) return

        when {
            fuel.editing -> if (fuel.editStep == 0) {
                when (n) { 1 -> fuel.editToggleType(); 2 -> fuel.editNext(); 3 -> fuel.editCancel() }
            } else {
                when (n) {
                    1 -> fuel.editStepLitres(DashConfig.FUEL_EDIT_STEP_L)
                    4 -> fuel.editStepLitres(-DashConfig.FUEL_EDIT_STEP_L)
                    2 -> if (fuel.editNext()) { trip.resetSinceRefuel(); showToast("FILL-UP SAVED") }
                    3 -> fuel.editCancel()
                }
            }
            tpms.pairing -> when (n) { 1 -> tpms.pairNext(); 2 -> tpms.pairAssign(); 4 -> tpms.pairSkip(); 3 -> tpms.pairCancel() }
            nav.menuOpen -> when (n) { 1 -> nav.menuNext(); 4 -> nav.menuPrev(); 2 -> nav.menuChoose(); 3 -> nav.menuCancel() }
            compact -> when (n) {
                1 -> compactSwap = !compactSwap
                2 -> swapMapApp()
                3 -> stepBrightness()
                4 -> platform.closeSplit()
            }
            else -> when (n) {
                1 -> page = Page.entries[(page.ordinal + 1) % Page.entries.size]
                3 -> stepBrightness()
                2 -> when (page) {
                    Page.FUEL -> openFillUp()
                    Page.TYRES -> tpms.pairOpen()
                    Page.GPS -> nav.dropWaypoint()
                    Page.DIAG -> toggleSource()
                    else -> {}
                }
                4 -> when (page) {
                    Page.MAIN -> openSplit()
                    Page.GPS -> nav.menuOpen()
                    Page.DIAG -> maintenancePress()
                    else -> {}
                }
            }
        }
    }

    /** Diagnostics: show or hide the throttle bar on the main page. */
    val showThrottle get() = platform.showThrottle
    fun toggleThrottle() {
        platform.showThrottle = !platform.showThrottle
        showToast(if (platform.showThrottle) "THROTTLE SHOWN" else "THROTTLE HIDDEN")
    }

    /** Asks for the hotspot name and password (set by MainActivity); the answer is null if cancelled. */
    var askHotspot: ((String, String, (Pair<String, String>?) -> Unit) -> Unit)? = null

    /** The node's update-mode status for Diagnostics, or null if it can't be updated from here. */
    val nodeUpdateStatus: String? get() = ble?.otaStatus
    val nodeUpdating get() = NodeOta.active(ble?.otaStatus)

    /** NODE UPDATE: starts the node's Wi-Fi update mode, or stops it if it's on. */
    fun nodeUpdatePress() {
        val b = ble ?: return showToast("NO BLUETOOTH")
        when {
            b.state != BleTelemetry.State.CONNECTED -> showToast("NODE NOT CONNECTED")
            b.otaStatus == null -> showToast("NODE FIRMWARE HAS NO UPDATE MODE")
            nodeUpdating -> { b.stopNodeOta(); showToast("NODE UPDATE STOPPED") }
            source == Source.LIVE && parser.latest.rpm > 0 -> showToast("STOP THE ENGINE FIRST")
            platform.hotspotSsid.isEmpty() -> editHotspot { nodeUpdatePress() }
            else -> {
                val problem = NodeOta.problem(platform.hotspotSsid, platform.hotspotPass)
                if (problem != null) { showToast(problem); return }
                b.startNodeOta(platform.hotspotSsid, platform.hotspotPass)
                showToast("TURN ON THE PHONE HOTSPOT")
            }
        }
    }

    /** Hold NODE UPDATE: change the hotspot details. [then] runs after they're saved. */
    fun editHotspot(then: (() -> Unit)? = null) {
        val ask = askHotspot ?: return
        ask(platform.hotspotSsid, platform.hotspotPass) { answer ->
            if (answer == null) return@ask
            val (ssid, pass) = answer.first.trim() to answer.second
            NodeOta.problem(ssid, pass)?.let { showToast(it); return@ask }
            platform.hotspotSsid = ssid
            platform.hotspotPass = pass
            showToast("HOTSPOT SAVED")
            then?.invoke()
        }
    }

    fun stepBrightness() {
        platform.brightnessIndex = (platform.brightnessIndex + 1) % DashConfig.BRIGHTNESS_LEVELS.size
        manualBright = true
        showToast("BRIGHTNESS ${platform.brightnessIndex + 1}/${DashConfig.BRIGHTNESS_LEVELS.size}")
    }

    /** SIM data must never reach the real fuel, trip, waypoint or battery records. */
    private fun applySource() {
        store.persist = source == Source.LIVE
        ble?.setNodeSim(source == Source.SIM)          // the CAN node follows: its test data in SIM
        parser.reset()
        simulator.restart()
        fuel.load(); trip.load(); battery.load(); nav.load(); tpms.load()
        vbatShown = 0f
    }

    private fun showToast(s: String) { toastText = s; toastUntil = clock() + 2000 }

    val toast: String?
        get() = nav.toast ?: if (clock() < toastUntil) toastText else null

    // ------------------------------------------------------------ brightness
    private fun updateBrightness() {
        var level = DashConfig.BRIGHTNESS_LEVELS[platform.brightnessIndex.coerceIn(0, DashConfig.BRIGHTNESS_LEVELS.size - 1)]
        if (DashConfig.NIGHT_DIM) {
            val t = localTime()
            val mins = t.hour * 60 + t.minute
            val night = if (DashConfig.NIGHT_START_MIN > DashConfig.NIGHT_END_MIN)
                mins >= DashConfig.NIGHT_START_MIN || mins < DashConfig.NIGHT_END_MIN
            else mins >= DashConfig.NIGHT_START_MIN && mins < DashConfig.NIGHT_END_MIN
            if (night != lastNight) { manualBright = false; lastNight = night }
            if (!manualBright && night) level = DashConfig.NIGHT_LEVEL
        }
        if (level != lastBrightness) { lastBrightness = level; platform.setBrightness(level) }
    }

    val nightDimmed get() = lastNight == true && !manualBright

    // ------------------------------------------------------------ warnings
    // Priority: tyres > battery bad > key on > battery weak
    private fun pickAlert(t: Telemetry, nowMs: Long) {
        var want: Alert? = null
        fun offer(id: Int, active: Boolean, bad: Boolean, l1: String, l2: String, timed: Boolean) {
            if (want != null || !active || nowMs < snoozeUntil[id]) {
                if (!active) shownSince[id] = 0
                return
            }
            if (shownSince[id] == 0L) shownSince[id] = nowMs
            if (timed && nowMs - shownSince[id] > 8000) {
                snoozeUntil[id] = nowMs + DashConfig.ALERT_SNOOZE_MS
                shownSince[id] = 0
                return
            }
            want = Alert(id, bad, l1, l2)
        }
        val f = tpms.front
        val r = tpms.rear
        val fLow = f.valid && !f.stale && f.bar < DashConfig.TPMS_FRONT_LOW_BAR
        val rLow = r.valid && !r.stale && r.bar < DashConfig.TPMS_REAR_LOW_BAR
        offer(AL_TYRE_F, fLow, true, "FRONT TYRE LOW", "%.0f psi (%.2f bar) - check for a puncture".format(f.bar * 14.5038f, f.bar), true)
        offer(AL_TYRE_R, rLow, true, "REAR TYRE LOW", "%.0f psi (%.2f bar) - check for a puncture".format(r.bar * 14.5038f, r.bar), true)
        val batt = "Cranked at %.1f V (rest %.1f V)".format(battery.lastMin, battery.lastRest)
        offer(AL_BATT, nowMs < battAlertUntil && battery.health == BattHealth.BAD, true, "BATTERY BAD", batt, false)
        val keyOn = keyOnSinceMs != 0L && nowMs - keyOnSinceMs >= DashConfig.KEYON_WARN_S * 1000
        offer(AL_KEYON, keyOn, false, "KEY ON, ENGINE OFF",
            if (t.has(Telem.FLAG_KILL_RUN)) "Turn the key off to save the battery" else "Kill switch is off - turn the key off too", false)
        offer(AL_BATT, nowMs < battAlertUntil && battery.health == BattHealth.WEAK, false, "BATTERY WEAK", batt, false)
        alert = want
    }

    companion object {
        const val AL_TYRE_F = 0
        const val AL_TYRE_R = 1
        const val AL_BATT = 2
        const val AL_KEYON = 3
    }
}
