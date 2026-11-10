// NavModel.kt - breadcrumb waypoints, snail trail and track back. Port of the ESP32
// dash's nav.cpp, fed from the phone's GPS.
package com.husky701.dash

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

data class NavPoint(val latE7: Int, val lonE7: Int)
data class Waypoint(val pos: NavPoint, val id: Int, val label: String = "") {
    /** The rider's name for it, or W<id>. */
    val name get() = label.ifBlank { "W$id" }
}

class NavModel(private val store: Store, private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    val trail = ArrayList<NavPoint>()
    val waypoints = ArrayList<Waypoint>()
    private var trailSpacingM = DashConfig.NAV_TRAIL_SPACING_M
    private var trailDirty = false
    private var lastTrailSaveMs = 0L
    private var nextId = 1
    private var lastUpdateMs = 0L

    var fix = false; private set
    var hasPosition = false; private set
    var position = NavPoint(0, 0); private set
    var sats = 0; private set
    var kmh = 0f; private set
    var headingValid = false; private set
    var headingDeg = 0f; private set
    var altitudeM = 0; private set
    var accuracyM = 0f; private set
    var source = "none"; private set
    /** Map scale: metres from the centre to the top/bottom edge. */
    var mapRadiusM = 200f; private set
    /** Map scale follows the trail and target. Off after a manual zoom until AUTO is pressed. */
    var autoZoom = true; private set

    var navigating = false; private set
    var arrived = false; private set
    var tracBack = false; private set
    private var navTrailIdx = 0
    private var navCurrent = 0
    private var navFinal = 0
    var targetDistM = 0f; private set
    var targetBearingDeg = 0f; private set

    var menuOpen = false; private set
    /** Touch: the waypoint row picked in the sheet (0 = none). */
    var selectedId = 0; private set
    /** A name is being typed: the sheet stays open. */
    var typing = false
        set(v) { field = v; menuLastInputMs = clock() }
    val selected: Waypoint? get() = waypoints.firstOrNull { it.id == selectedId }
    var menuIndex = 0; private set
    private var clearConfirm = false
    private var menuLastInputMs = 0L

    private var toastText = ""
    private var toastUntil = 0L

    init { load() }

    fun load() {
        nextId = store.int("nav.nextId", 1)
        waypoints.clear()
        store.string("nav.wp", "").split(';').forEach { s ->
            val p = s.split(',')
            if (p.size >= 3) waypoints.add(Waypoint(NavPoint(p[0].toInt(), p[1].toInt()), p[2].toInt(), p.getOrElse(3) { "" }))
        }
        trail.clear()
        store.string("nav.trail", "").split(';').forEach { s ->
            val p = s.split(',')
            if (p.size == 2) trail.add(NavPoint(p[0].toInt(), p[1].toInt()))
        }
        trailSpacingM = store.float("nav.spacing", DashConfig.NAV_TRAIL_SPACING_M)
        stopNavigation()
        menuOpen = false
        lastTrailSaveMs = clock()
    }

    fun update(g: GpsSample) {
        val now = clock()
        if (menuOpen && !typing && now - menuLastInputMs > DashConfig.NAV_MENU_TIMEOUT_MS) menuCancel()
        if (now - lastUpdateMs < 200) return
        lastUpdateMs = now

        fix = g.fix
        sats = g.sats
        source = g.source
        kmh = if (g.fix) g.kmh else 0f
        if (g.fix) {
            altitudeM = g.altM
            accuracyM = g.accuracyM
            position = NavPoint(g.latE7, g.lonE7)
            hasPosition = true
            headingValid = g.courseValid && g.kmh >= DashConfig.NAV_HEADING_MIN_KMH
            if (headingValid) headingDeg = g.courseDeg
            addTrailPoint()
            updateNavigation()
            updateMapScale()
        } else {
            headingValid = false
        }
        if (trailDirty && now - lastTrailSaveMs >= DashConfig.NAV_TRAIL_SAVE_MS) saveTrail()
    }

    private fun addTrailPoint() {
        if (trail.isNotEmpty()) {
            val (x, y) = offsetMeters(trail.last(), position)
            if (x * x + y * y < trailSpacingM * trailSpacingM) return
        }
        if (trail.size >= DashConfig.NAV_TRAIL_MAX) {
            val kept = trail.filterIndexed { i, _ -> i % 2 == 0 }
            trail.clear(); trail.addAll(kept)
            trailSpacingM *= 2
            navTrailIdx /= 2
        }
        trail.add(position)
        trailDirty = true
    }

    val targetPos: NavPoint get() = if (tracBack) trail[navTrailIdx] else waypoints[navCurrent].pos
    val targetId: Int get() = if (tracBack || !navigating) 0 else waypoints[navCurrent].id
    val finalId: Int get() = if (tracBack || !navigating) 0 else waypoints[navFinal].id

    private fun updateNavigation() {
        if (!navigating) return
        val (x, y) = offsetMeters(position, targetPos)
        targetDistM = sqrt(x * x + y * y)
        targetBearingDeg = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat().let { if (it < 0) it + 360f else it }

        if (tracBack) {
            if (targetDistM < DashConfig.NAV_ARRIVE_M) {
                if (navTrailIdx > 0) navTrailIdx-- else arrived = true
            }
            return
        }
        if (targetDistM < DashConfig.NAV_ARRIVE_M) {
            if (navCurrent > navFinal) { navCurrent--; arrived = false } else arrived = true
        } else if (targetDistM > DashConfig.NAV_ARRIVE_M * 2) {
            arrived = false
        }
    }

    private val radii = floatArrayOf(100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f, 50000f)

    fun zoom(dir: Int) {
        autoZoom = false
        val i = radii.indexOfFirst { it >= mapRadiusM }.let { if (it < 0) radii.size - 1 else it }
        mapRadiusM = radii[(i + dir).coerceIn(0, radii.size - 1)]
    }

    /** Pinch: fingers apart (factor > 1) zooms in. */
    fun zoomScale(factor: Float) {
        if (factor <= 0f) return
        autoZoom = false
        mapRadiusM = (mapRadiusM / factor).coerceIn(radii.first(), radii.last())
    }

    fun zoomAuto() { autoZoom = true; updateMapScale() }

    private fun updateMapScale() {
        if (!autoZoom) return
        var need = 0f
        if (navigating) need = max(need, targetDistM)
        for (i in max(0, trail.size - DashConfig.NAV_MAP_RECENT_POINTS) until trail.size) {
            val (x, y) = offsetMeters(position, trail[i])
            need = max(need, max(abs(x), abs(y)))
        }
        need *= 1.25f                       // leave a margin round the edge
        var best = radii.last()
        for (r in radii.reversed()) if (r >= need) best = r
        if (best > mapRadiusM || need < best * 0.7f) mapRadiusM = best
    }

    // ---- taps ----
    fun dropWaypoint() {
        if (!fix) { showToast("NO GPS FIX"); return }
        if (waypoints.size >= DashConfig.NAV_WAYPOINT_MAX) {
            waypoints.removeAt(0)
            if (navigating && !tracBack) {
                if (navFinal == 0) stopNavigation() else { navFinal--; navCurrent-- }
            }
        }
        waypoints.add(Waypoint(position, nextId))
        showToast("W$nextId SAVED")
        nextId++
        saveWaypoints()
    }

    private fun startNavigation(index: Int) {
        var nearest = index
        var nearestSq = Float.MAX_VALUE
        for (i in index until waypoints.size) {
            val (x, y) = offsetMeters(position, waypoints[i].pos)
            val d = x * x + y * y
            if (d < nearestSq) { nearestSq = d; nearest = i }
        }
        navFinal = index
        navCurrent = nearest
        tracBack = false
        navigating = true
        arrived = false
        updateNavigation()
    }

    private fun startTracBack() {
        var nearest = trail.size - 1
        var nearestSq = Float.MAX_VALUE
        for (i in trail.indices) {
            val (x, y) = offsetMeters(position, trail[i])
            val d = x * x + y * y
            if (d < nearestSq) { nearestSq = d; nearest = i }
        }
        navTrailIdx = nearest
        tracBack = true
        navigating = true
        arrived = false
        updateNavigation()
    }

    private fun stopNavigation() { navigating = false; arrived = false; tracBack = false }

    private fun clearAll() {
        stopNavigation()
        waypoints.clear()
        nextId = 1
        trail.clear()
        trailSpacingM = DashConfig.NAV_TRAIL_SPACING_M
        saveWaypoints()
        saveTrail()
        showToast("ALL CLEARED")
    }

    // ---- menu: [Stop navigating], [TracBack], waypoints newest first, Clear all ----
    private val hasTracBackItem get() = trail.size >= 2
    val menuCount get() = waypoints.size + 1 + (if (navigating) 1 else 0) + (if (hasTracBackItem) 1 else 0)

    fun menuLabel(itemIn: Int): String {
        var item = itemIn
        if (navigating) { if (item == 0) return "Stop navigating"; item-- }
        if (hasTracBackItem) { if (item == 0) return "TracBack: retrace track"; item-- }
        if (item < waypoints.size) {
            val w = waypoints[waypoints.size - 1 - item]
            if (!fix) return w.name
            val (x, y) = offsetMeters(position, w.pos)
            val brg = (Math.toDegrees(kotlin.math.atan2(x.toDouble(), y.toDouble())).toFloat() + 360f) % 360f
            val here = if (navigating && !tracBack && w.id == targetId) "  ◀ NAV" else ""
            return "${w.name}   ${formatDist(sqrt(x * x + y * y))}  ${compassPoint(brg)}$here"
        }
        return if (clearConfirm) "Tap again: clear all" else "Clear waypoints + trail"
    }

    private fun touchMenu() { menuLastInputMs = clock(); clearConfirm = false }
    fun menuOpen() { menuOpen = true; menuIndex = 0; selectedId = 0; touchMenu() }
    /** Touch: open the sheet with this waypoint already selected (a tap on its map marker). */
    fun menuOpenAt(id: Int) { menuOpen(); if (waypoints.any { it.id == id }) selectedId = id }
    fun menuNext() { menuIndex = (menuIndex + 1) % menuCount; touchMenu() }
    fun menuPrev() { menuIndex = (menuIndex + menuCount - 1) % menuCount; touchMenu() }
    fun menuCancel() { menuOpen = false; clearConfirm = false; selectedId = 0 }

    /** The waypoint on menu row i, or null for the other rows. */
    fun menuWaypoint(itemIn: Int): Waypoint? {
        var item = itemIn
        if (navigating) { if (item == 0) return null; item-- }
        if (hasTracBackItem) { if (item == 0) return null; item-- }
        return if (item in waypoints.indices) waypoints[waypoints.size - 1 - item] else null
    }

    /** Touch: a waypoint row selects it (tap again to unselect); the other rows act straight away. */
    fun menuSelect(i: Int) {
        if (i !in 0 until menuCount) return
        menuWaypoint(i)?.let { w ->
            selectedId = if (selectedId == w.id) 0 else w.id
            menuIndex = i
            touchMenu()
            return
        }
        selectedId = 0
        val keepConfirm = clearConfirm && i == menuIndex
        menuIndex = i
        menuLastInputMs = clock()
        clearConfirm = keepConfirm
        menuChoose()
    }

    /** Touch: navigate to the selected waypoint. */
    fun navigateSelected() {
        val i = waypoints.indexOfFirst { it.id == selectedId }
        if (i < 0) return
        if (!fix) { showToast("NO GPS FIX"); return }
        startNavigation(i)
        menuCancel()
    }

    /** Give a waypoint a name (blank = back to W<id>). ';' and ',' are used by storage, so they become spaces. */
    fun renameWaypoint(id: Int, raw: String) {
        val i = waypoints.indexOfFirst { it.id == id }
        if (i < 0) return
        val label = raw.replace(Regex("[;,\\s]+"), " ").trim().take(DashConfig.NAV_LABEL_MAX)
        waypoints[i] = waypoints[i].copy(label = label)
        saveWaypoints()
        showToast("${waypoints[i].name.uppercase()} SAVED")
    }

    fun nameOf(id: Int) = waypoints.firstOrNull { it.id == id }?.name ?: "W$id"

    fun menuChoose() {
        menuLastInputMs = clock()
        var item = menuIndex
        if (navigating) { if (item == 0) { stopNavigation(); menuCancel(); return }; item-- }
        if (hasTracBackItem) {
            if (item == 0) { if (!fix) showToast("NO GPS FIX") else startTracBack(); menuCancel(); return }
            item--
        }
        if (item < waypoints.size) {
            if (!fix) showToast("NO GPS FIX") else startNavigation(waypoints.size - 1 - item)
            menuCancel()
            return
        }
        if (!clearConfirm) { clearConfirm = true; return }
        clearAll()
        menuCancel()
    }

    // ---- storage ----
    private fun saveWaypoints() {
        store.save("nav.wp" to waypoints.joinToString(";") {
                "${it.pos.latE7},${it.pos.lonE7},${it.id}" + if (it.label.isNotEmpty()) ",${it.label}" else "" },
            "nav.nextId" to nextId)
    }

    fun saveTrail() {
        lastTrailSaveMs = clock()
        store.save("nav.trail" to trail.joinToString(";") { "${it.latE7},${it.lonE7}" }, "nav.spacing" to trailSpacingM)
        trailDirty = false
    }

    private fun showToast(s: String) { toastText = s; toastUntil = clock() + DashConfig.NAV_TOAST_MS }
    val toast: String? get() = if (clock() < toastUntil) toastText else null

    companion object {
        private const val METERS_PER_E7 = 0.0111319491

        /** Metres from a to b: x east, y north. */
        /** Degrees and decimal minutes with the hemisphere first: "S 33° 23.946′", "E 151° 20.395′". 0.001′ is about 2 m. */
        fun formatDM(e7: Int, isLat: Boolean): String {
            val hemi = if (isLat) (if (e7 < 0) "S" else "N") else (if (e7 < 0) "W" else "E")
            val deg = kotlin.math.abs(e7) / 1e7
            var d = deg.toInt()
            var m = Math.round((deg - d) * 60_000.0) / 1000.0
            if (m >= 60.0) { d += 1; m = 0.0 }
            return String.format(java.util.Locale.US, "%s %d° %06.3f′", hemi, d, m)
        }

        fun distM(a: NavPoint, b: NavPoint): Float {
            val (x, y) = offsetMeters(a, b)
            return sqrt(x * x + y * y)
        }

        fun offsetMeters(a: NavPoint, b: NavPoint): Pair<Float, Float> {
            val midLat = (a.latE7.toLong() + b.latE7) / 2 * 1e-7
            val x = (b.lonE7.toLong() - a.lonE7) * METERS_PER_E7 * cos(Math.toRadians(midLat))
            val y = (b.latE7.toLong() - a.latE7) * METERS_PER_E7
            return x.toFloat() to y.toFloat()
        }

        private val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        fun compassPoint(deg: Float): String = points[(((deg + 22.5f) % 360f + 360f) % 360f / 45f).toInt() % 8]

        fun formatDist(m: Float): String = when {
            m < 1000 -> "%.0f m".format(java.util.Locale.US, m)
            m < 10000 -> "%.2f km".format(java.util.Locale.US, m / 1000f)
            else -> "%.1f km".format(java.util.Locale.US, m / 1000f)
        }
    }
}
