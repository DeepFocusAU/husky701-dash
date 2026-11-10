// Models.kt - fuel estimate, trip figures and battery health. Ports of the ESP32 dash's
// fuel.cpp, trip.cpp and battery.cpp, with the same rules and settings.
package com.husky701.dash

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun clampf(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v

// ============================================================================ fuel
/**
 * No fuel sensor: flow is modelled as  k x rpm/1000 x (idle load + throttle/255), with no
 * fuel on overrun. k is learned at each FULL fill-up where the pumped litres are entered.
 */
class FuelModel(private val store: Store, private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    // persisted
    var litres = 0f; private set
    private var k = 0f
    private var unitsSinceFull = 0f
    private var addedSinceFull = 0f
    var usedSinceFill = 0f; private set
    var kmSinceFill = 0f; private set
    var learnedFills = 0; private set
    private var haveFullRef = false
    private var histL100 = 0f

    // runtime
    private var lastMs = 0L
    private var lastSaveMs = 0L
    private var dirty = false
    var rateLph = 0f; private set
    private var speedSm = 0f
    var totalUsed = 0f; private set

    // fill-up editor
    var editing = false; private set
    var editStep = 0; private set
    var editFull = true; private set
    var editLitres = 0f; private set
    private var editMs = 0L
    var editBlocked = false; private set
    private var blockedMs = 0L

    init { load() }

    fun load() {
        k = store.float("fuel.k", DashConfig.FUEL_K_DEFAULT)
        litres = store.float("fuel.litres", DashConfig.FUEL_TANK_L)
        unitsSinceFull = store.float("fuel.uFull", 0f)
        addedSinceFull = store.float("fuel.addFull", 0f)
        usedSinceFill = store.float("fuel.used", 0f)
        kmSinceFill = store.float("fuel.km", 0f)
        learnedFills = store.int("fuel.nLearn", 0)
        haveFullRef = store.bool("fuel.fullRef", false)
        histL100 = store.float("fuel.hist", 0f)
        if (!(k > DashConfig.FUEL_K_DEFAULT * 0.3f && k < DashConfig.FUEL_K_DEFAULT * 3f)) k = DashConfig.FUEL_K_DEFAULT
        litres = clampf(litres, 0f, DashConfig.FUEL_TANK_L)
        editing = false
        lastMs = clock(); lastSaveMs = lastMs
    }

    fun update(engineData: Boolean, rpm: Int, throttle: Int, speedKmh: Float) {
        val now = clock()
        val dt = (now - lastMs) / 1000f
        lastMs = now
        if (dt <= 0f || dt > 2f) return

        var lph = 0f
        if (engineData && rpm > 300) {
            val overrun = throttle < DashConfig.FUEL_CUT_THROTTLE && rpm > DashConfig.FUEL_CUT_RPM &&
                speedKmh > DashConfig.FUEL_CUT_MIN_KMH
            val load = if (overrun) 0f else DashConfig.FUEL_IDLE_LOAD + throttle / 255f
            val units = rpm / 1000f * load * dt
            val l = units * k
            unitsSinceFull += units
            usedSinceFill += l
            totalUsed += l
            litres = max(0f, litres - l)
            lph = l / dt * 3600f
            dirty = true
        }
        if (engineData && speedKmh > 1f) {
            kmSinceFill += speedKmh * dt / 3600f
            dirty = true
        }
        val a = min(1f, dt / 2f)
        rateLph += (lph - rateLph) * a
        speedSm += ((if (engineData) speedKmh else 0f) - speedSm) * a

        if (editing && now - editMs > DashConfig.FUEL_EDIT_TIMEOUT_MS) editing = false
        if (editBlocked && now - blockedMs > 2500) editBlocked = false
        save(false)
    }

    fun save(force: Boolean) {
        val now = clock()
        if (!force && (!dirty || now - lastSaveMs < DashConfig.FUEL_SAVE_EVERY_MS)) return
        store.save("fuel.k" to k, "fuel.litres" to litres, "fuel.uFull" to unitsSinceFull,
            "fuel.addFull" to addedSinceFull, "fuel.used" to usedSinceFill, "fuel.km" to kmSinceFill,
            "fuel.nLearn" to learnedFills, "fuel.fullRef" to haveFullRef, "fuel.hist" to histL100)
        lastSaveMs = now
        dirty = false
    }

    fun fillUp(full: Boolean, litresAddedIn: Float) {
        if (!full && litresAddedIn < 0f) {
            // fuel taken out (drained, or given to a mate): lower the level, and count it against
            // what was added since the last full tank so the next full fill still learns right.
            // It's not a fill-up, so the since-fill figures carry on.
            litres = max(0f, litres + litresAddedIn)
            addedSinceFull += litresAddedIn
            dirty = true
            save(true)
            return
        }
        val litresAdded = max(0f, litresAddedIn)
        if (kmSinceFill >= DashConfig.FUEL_HIST_MIN_KM) {
            val tankL100 = usedSinceFill / kmSinceFill * 100f
            histL100 = if (histL100 > 0) histL100 + (tankL100 - histL100) * DashConfig.FUEL_HIST_RATE else tankL100
        }
        if (full) {
            if (haveFullRef && litresAdded > 0 && unitsSinceFull > 1f) {
                val actual = addedSinceFull + litresAdded
                val estimated = unitsSinceFull * k
                if (actual >= DashConfig.FUEL_LEARN_MIN_L && estimated > 0) {
                    val ratio = clampf(actual / estimated, 0.6f, 1.6f)
                    k += (k * ratio - k) * DashConfig.FUEL_LEARN_RATE
                    k = clampf(k, DashConfig.FUEL_K_DEFAULT * 0.3f, DashConfig.FUEL_K_DEFAULT * 3f)
                    if (learnedFills < 255) learnedFills++
                }
            }
            litres = DashConfig.FUEL_TANK_L
            unitsSinceFull = 0f
            addedSinceFull = 0f
            haveFullRef = true
        } else {
            litres = min(DashConfig.FUEL_TANK_L, litres + litresAdded)
            addedSinceFull += litresAdded
        }
        usedSinceFill = 0f
        kmSinceFill = 0f
        dirty = true
        save(true)
    }

    // ---- fill-up editor ----
    /** Opens at any speed (a pillion or a stop-and-go fuel queue). [speedKmh] is kept for the call sites. */
    @Suppress("UNUSED_PARAMETER")
    fun editOpen(speedKmh: Float) {
        editing = true
        editStep = 0
        editFull = true
        val used = DashConfig.FUEL_TANK_L - litres
        editLitres = (used / DashConfig.FUEL_EDIT_STEP_L).roundToInt() * DashConfig.FUEL_EDIT_STEP_L
        editMs = clock()
    }
    fun editCancel() { editing = false }
    fun editSetFull(full: Boolean) { editFull = full; editClamp(); editMs = clock() }
    /** FULL TANK: 0 to a tank. PARTIAL: minus what's in the tank (taken out) to a tank. */
    private fun editClamp() { editLitres = clampf(editLitres, if (editFull) 0f else -litres, DashConfig.FUEL_TANK_L) }
    /** Touch: save straight away. Returns true when saved. */
    fun editSave(): Boolean {
        if (!editing) return false
        fillUp(editFull, editLitres)
        editing = false
        return true
    }
    fun editToggleType() { editFull = !editFull; editClamp(); editMs = clock() }
    fun editStepLitres(delta: Float) { editLitres += delta; editClamp(); editMs = clock() }
    /** Returns true when the fill-up was saved. */
    fun editNext(): Boolean {
        editMs = clock()
        if (editStep == 0) { editStep = 1; return false }
        fillUp(editFull, editLitres)
        editing = false
        return true
    }

    // ---- derived ----
    val capacity get() = DashConfig.FUEL_TANK_L
    val avgL100 get() = if (kmSinceFill >= DashConfig.FUEL_AVG_MIN_KM) usedSinceFill / kmSinceFill * 100f else 0f
    val instL100 get() = if (speedSm > 5f) rateLph / speedSm * 100f else 0f
    val kRatio get() = k / DashConfig.FUEL_K_DEFAULT

    enum class Warn { NONE, AMBER, RED, FLASH }
    /** Range left as a share of the full-tank range. Both use the same L/100, so this is the tank share. */
    val rangeFraction get() = if (capacity > 0f) litres / capacity else 0f
    val warn: Warn
        get() = when {
            rangeFraction < DashConfig.FUEL_FLASH_FRACTION -> Warn.FLASH
            rangeFraction < DashConfig.FUEL_RED_FRACTION -> Warn.RED
            rangeKm < DashConfig.FUEL_LOW_AMBER_KM -> Warn.AMBER
            else -> Warn.NONE
        }
    val rangeKm: Float
        get() {
            val past = if (histL100 > 0) histL100 else DashConfig.FUEL_DEFAULT_L100
            val blend = clampf(kmSinceFill / DashConfig.FUEL_RANGE_BLEND_KM, 0f, 1f)
            val l100 = if (avgL100 > 0) past + (avgL100 - past) * blend else past
            return litres / l100 * 100f
        }
}

// ============================================================================ trip
class TripSet(var km: Float = 0f, var fuelL: Float = 0f, var movingS: Float = 0f, var maxKmh: Float = 0f) {
    val avgKmh get() = if (movingS > 30) km / (movingS / 3600f) else 0f
    val avgL100 get() = if (km >= DashConfig.FUEL_AVG_MIN_KM) fuelL / km * 100f else 0f
    fun add(speedKmh: Float, dt: Float) {
        if (speedKmh > 1f) km += speedKmh * dt / 3600f
        if (speedKmh > DashConfig.TRIP_MOVING_KMH) movingS += dt
        if (speedKmh > maxKmh) maxKmh = speedKmh
    }
}

/** Today (from the phone's local date) and since the last fill-up. */
class TripModel(private val store: Store, private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    var today = TripSet(); private set
    var sinceRefuel = TripSet(); private set
    private var day = 0L
    private var lastMs = 0L
    private var lastSaveMs = 0L
    private var dirty = false
    private var lastFuel = -1f

    init { load() }

    private fun loadSet(p: String) = TripSet(store.float("$p.km", 0f), store.float("$p.fuel", 0f),
        store.float("$p.mov", 0f), store.float("$p.max", 0f))

    fun load() {
        day = store.long("trip.day", 0L)
        today = loadSet("trip.d")
        sinceRefuel = loadSet("trip.r")
        lastMs = clock(); lastSaveMs = lastMs
        lastFuel = -1f
    }

    /** localDay = days since 1970-01-01 in local time (always known on the phone). */
    fun update(engineData: Boolean, speedKmh: Float, fuelUsedL: Float, localDay: Long) {
        val now = clock()
        val dt = (now - lastMs) / 1000f
        lastMs = now
        if (localDay != day) {
            if (day != 0L) today = TripSet()
            day = localDay
            dirty = true
            save(true)
        }
        if (lastFuel >= 0 && fuelUsedL > lastFuel) {
            val used = fuelUsedL - lastFuel
            today.fuelL += used
            sinceRefuel.fuelL += used
            dirty = true
        }
        lastFuel = fuelUsedL
        if (engineData && dt > 0 && dt <= 2f) {
            today.add(speedKmh, dt)
            sinceRefuel.add(speedKmh, dt)
            if (speedKmh > 1f) dirty = true
        }
        save(false)
    }

    fun resetSinceRefuel() {
        sinceRefuel = TripSet()
        dirty = true
        save(true)
    }

    private fun save(force: Boolean) {
        val now = clock()
        if (!dirty || (!force && now - lastSaveMs < DashConfig.TRIP_SAVE_EVERY_MS)) return
        store.save("trip.day" to day,
            "trip.d.km" to today.km, "trip.d.fuel" to today.fuelL, "trip.d.mov" to today.movingS, "trip.d.max" to today.maxKmh,
            "trip.r.km" to sinceRefuel.km, "trip.r.fuel" to sinceRefuel.fuelL, "trip.r.mov" to sinceRefuel.movingS, "trip.r.max" to sinceRefuel.maxKmh)
        lastSaveMs = now
        dirty = false
    }
}

// ============================================================================ battery health
enum class BattHealth { UNKNOWN, GOOD, WEAK, BAD }

/** Resting voltage before each start and the lowest voltage while cranking. */
class BatteryMonitor(private val store: Store, private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private enum class St { OFF, CRANK, RUNNING }
    private var st = St.OFF
    private var restV = 0f
    private var minV = 99f
    var lastRest = 0f; private set
    var lastMin = 0f; private set
    val history = ArrayList<Float>()          // cranking minima, newest first, max 5
    private var runSinceMs = 0L
    private var newResult = false

    init { load() }

    fun load() {
        history.clear()
        store.string("batt.hist", "").split(',').mapNotNull { it.toFloatOrNull() }.take(5).forEach { history.add(it) }
        lastRest = store.float("batt.rest", 0f)
        lastMin = history.firstOrNull() ?: 0f
        st = St.OFF
    }

    fun update(vbatOk: Boolean, vbat: Float, rpm: Int) {
        if (!vbatOk) return
        val now = clock()
        when (st) {
            St.OFF -> {
                restV = if (restV == 0f) vbat else restV + (vbat - restV) * 0.2f
                if (rpm > 0) { st = St.CRANK; minV = vbat; runSinceMs = 0 }
            }
            St.CRANK -> {
                if (vbat < minV) minV = vbat
                if (rpm == 0) { finishStart(); st = St.OFF }
                else if (rpm > 1000) {
                    if (runSinceMs == 0L) runSinceMs = now
                    if (now - runSinceMs > 1000) { finishStart(); st = St.RUNNING }
                } else runSinceMs = 0
            }
            St.RUNNING -> if (rpm == 0) { st = St.OFF; restV = 0f }
        }
    }

    private fun finishStart() {
        if (minV > 20 || minV < 3) return
        lastRest = restV
        lastMin = minV
        history.add(0, minV)
        while (history.size > 5) history.removeAt(history.size - 1)
        store.save("batt.hist" to history.joinToString(","), "batt.rest" to lastRest)
        newResult = true
    }

    val health: BattHealth
        get() = when {
            history.isEmpty() -> BattHealth.UNKNOWN
            lastMin < DashConfig.BATT_CRANK_BAD_V -> BattHealth.BAD
            lastMin < DashConfig.BATT_CRANK_WEAK_V || lastRest < DashConfig.BATT_REST_WEAK_V -> BattHealth.WEAK
            else -> BattHealth.GOOD
        }

    /** True once, right after a start has been measured. */
    fun takeNewResult(): Boolean = newResult.also { newResult = false }
}
