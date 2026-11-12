// DashView.kt - draws the dash pages on the Note 10's screen, portrait (1080x2280).
//
// Layout follows a modern portrait adventure-bike TFT: vertical tacho down the left,
// status chips and a gear box top right, gauges, and a big speed figure at the bottom.
// Styling is a dark slate gradient, a pale sage segmented tacho that widens with rpm,
// a red zone, a yellow gear on a grey block and thin orange accents.
//
// Pages (1 tap cycles): Main -> Fuel -> Trip -> Tyres -> Map -> GPS data -> Diagnostics.
// All logic lives in Dash; this class only draws.
package com.husky701.dash

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@SuppressLint("ViewConstructor")
class DashView(context: Context, private val dash: Dash) : View(context) {

    /** Draw the minimalist tacho strip instead of the full dash. */
    var compact = false

    // ---- palette ----
    private val bgTop = Color.rgb(36, 50, 57)
    private val bgBottom = Color.rgb(10, 16, 19)
    private val text = Color.WHITE
    private val label = Color.rgb(150, 165, 168)
    private val faint = Color.rgb(70, 86, 90)
    private val panel = Color.argb(120, 8, 14, 17)
    private val orange = Color.rgb(242, 160, 30)
    private val sage = Color.rgb(196, 210, 186)
    private val sageEdge = Color.rgb(120, 140, 128)
    private val segOff = Color.argb(150, 20, 30, 34)
    private val segOffEdge = Color.rgb(78, 96, 92)
    private val red = Color.rgb(222, 48, 44)
    private val redOff = Color.rgb(92, 30, 30)
    private val amber = Color.rgb(255, 191, 0)
    private val amberOff = Color.rgb(84, 66, 20)
    private val blue = Color.rgb(60, 140, 255)
    private val blueOff = Color.rgb(22, 44, 78)
    private val blueEdge = Color.rgb(130, 180, 255)
    private val green = Color.rgb(70, 230, 90)
    private val cyan = Color.rgb(60, 220, 230)
    private val gearBlock = Color.rgb(58, 70, 74)
    private val yellow = Color.rgb(255, 212, 0)

    private val bgPaint = Paint()
    private val glowPaint = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    private val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val rect = RectF()
    private val path = Path()

    private val frameLoop = object : Runnable {
        override fun run() {
            dash.tick()
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postOnAnimation(frameLoop) }
    override fun onDetachedFromWindow() { removeCallbacks(frameLoop); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bgPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), bgTop, bgBottom, Shader.TileMode.CLAMP)
        glowPaint.shader = RadialGradient(w * 0.62f, h * 0.30f, w * 0.75f,
            Color.argb(60, 120, 150, 155), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    // ---- touch: buttons are registered while drawing; swipe left/right changes page ----
    /** [hold]: what a long press does, if anything. */
    private class Btn(val r: RectF, val hold: (() -> Unit)? = null, val action: () -> Unit)
    private val buttons = ArrayList<Btn>()
    private var downX = 0f
    private var downY = 0f
    private var navFirst = 0          // first row shown in the waypoint sheet

    private var multiTouch = false
    private var held = false          // a long press fired: the lift is not a tap
    private val holdTimer = Runnable {
        val b = buttons.lastOrNull { it.r.contains(downX, downY) }?.hold ?: return@Runnable
        held = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        b()
        invalidate()
    }
    private val pinch by lazy {
        android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean { dash.nav.zoomScale(d.scaleFactor); return true }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!compact && dash.page == Dash.Page.GPS && !dash.overlayOpen) pinch.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; multiTouch = false; held = false
                removeCallbacks(holdTimer)
                if (buttons.lastOrNull { it.r.contains(e.x, e.y) }?.hold != null)
                    postDelayed(holdTimer, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(e.x - downX) > W * 0.04f || kotlin.math.abs(e.y - downY) > W * 0.04f) removeCallbacks(holdTimer)
            MotionEvent.ACTION_CANCEL -> removeCallbacks(holdTimer)
            MotionEvent.ACTION_POINTER_DOWN -> { multiTouch = true; removeCallbacks(holdTimer) }   // pinch: no swipe or tap
            MotionEvent.ACTION_UP -> if (removeCallbacks(holdTimer).let { !multiTouch && !held }) {
                val dx = e.x - downX
                val dy = e.y - downY
                val slop = W * 0.04f
                if (!compact && kotlin.math.abs(dx) > W * 0.15f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.3f) {
                    if (!dash.overlayOpen) {
                        dash.swipe(if (dx < 0) 1 else -1)
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                } else if (compact && kotlin.math.abs(dx) > W * 0.15f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.3f) {
                    // strip: swipe left or right = the other map app
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    dash.swapMapApp()
                } else if (dash.nav.menuOpen && kotlin.math.abs(dy) > W * 0.08f && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                    navFirst += if (dy < 0) 4 else -4          // swipe the waypoint list
                } else if (kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.3f &&
                           kotlin.math.abs(dy) > (if (compact) H * 0.25f else H * 0.10f)) {
                    // swipe down: map + strip; swipe up on the strip: back to the full dash
                    if (compact && dy < 0) { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); dash.closeSplit() }
                    else if (!compact && dy > 0 && !dash.overlayOpen) { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); dash.openSplit() }
                } else if (kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop) {
                    // last drawn = on top
                    for (i in buttons.indices.reversed()) {
                        if (buttons[i].r.contains(e.x, e.y)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            buttons[i].action()
                            break
                        }
                    }
                }
            }
        }
        return true
    }

    /** A touch button: drawn now, active until the next frame. */
    private fun button(c: Canvas, l: Float, t: Float, r: Float, b: Float, label: String,
                       primary: Boolean = false, selected: Boolean = false, enabled: Boolean = true,
                       hold: (() -> Unit)? = null, action: () -> Unit) {
        val box = RectF(l, t, r, b)
        val h = b - t
        val rad = h * 0.22f
        when {
            selected -> { fill.color = sage; c.drawRoundRect(box, rad, rad, fill) }
            primary -> { fill.color = orange; c.drawRoundRect(box, rad, rad, fill) }
            else -> {
                fill.color = Color.argb(200, 30, 42, 47)
                c.drawRoundRect(box, rad, rad, fill)
                stroke.color = if (enabled) Color.argb(160, 150, 170, 172) else faint
                stroke.strokeWidth = 2.5f
                c.drawRoundRect(box, rad, rad, stroke)
            }
        }
        labels.textAlign = Paint.Align.CENTER
        labels.isFakeBoldText = true
        labels.textSize = h * 0.40f
        val tw = labels.measureText(label)
        if (tw > (r - l) * 0.88f) labels.textSize *= (r - l) * 0.88f / tw
        labels.color = when { selected || primary -> Color.BLACK; enabled -> text; else -> faint }
        c.drawText(label, box.centerX(), box.centerY() + labels.textSize * 0.36f, labels)
        labels.isFakeBoldText = false
        if (enabled) buttons.add(Btn(box, hold, action))
    }

    /** Colour for anything that comes from the bike's CAN bus: grey when there's no CAN data. */
    private fun canCol(col: Int) = if (dash.canOk) col else faint

    private fun flashOn() = (SystemClock.elapsedRealtime() / DashConfig.ALARM_FLASH_MS) % 2 == 0L
    private fun shiftFlashOn() = (SystemClock.elapsedRealtime() / DashConfig.SHIFT_FLASH_MS) % 2 == 0L
    private val alive get() = dash.alive
    private val t get() = dash.parser.latest
    private val W get() = width.toFloat()
    private val H get() = height.toFloat()

    /** Main page: white when all is well, the status colour otherwise. */
    private fun mainColour(l: Level): Int = if (l == Level.GOOD) text else levelColour(l)

    private fun levelColour(l: Level): Int = when (l) {
        Level.NONE -> label
        Level.COLD -> blue
        Level.GOOD -> sage
        Level.WARN -> amber
        Level.BAD -> if (flashOn()) red else faint
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, W, H, bgPaint)
        buttons.clear()
        // behind the map window the activity draws plain background; the overlay draws the dash
        if (SplitScreen.showing && !SplitScreen.isOverlay(this)) return
        c.drawRect(0f, 0f, W, H, glowPaint)
        if (compact) {
            drawCompact(c)
            dash.alert?.let { drawAlert(c, it) }
            dash.toast?.let { drawToast(c, it) }
            return
        }
        when (dash.page) {
            Dash.Page.MAIN -> drawMain(c)
            Dash.Page.FUEL -> drawFuel(c)
            Dash.Page.TRIP -> drawTrip(c)
            Dash.Page.TYRES -> drawTyres(c)
            Dash.Page.GPS -> drawMap(c)
            Dash.Page.DIAG -> drawDiag(c)
        }
        drawPageDots(c)
        if (dash.page == Dash.Page.MAIN) {
            if (!alive) drawNoLink(c, "NO LINK", H * 0.56f, H * 0.07f)
            else if (!dash.canOk) drawNoLink(c, "NO CAN DATA", H * 0.56f, H * 0.07f)
        }
        if (dash.fuel.editing) drawFuelEditor(c)
        if (dash.tpms.pairing) drawPairing(c)
        if (dash.nav.menuOpen) drawNavMenu(c)
        dash.alert?.let { drawAlert(c, it) }
        dash.toast?.let { drawToast(c, it) }
    }

    // ================================================================== shared pieces
    private fun clockText(): String = String.format(Locale.US, "%02d:%02d", dash.now.hour, dash.now.minute)

    /** Title (orange) top left, clock top right, orange rule underneath. Optional centre text. */
    private fun drawHeader(c: Canvas, title: String, centre: String? = null, centreCol: Int = text) {
        val base = H * 0.058f
        labels.textAlign = Paint.Align.LEFT
        labels.textSize = H * 0.030f
        labels.color = orange
        labels.isFakeBoldText = true
        c.drawText(title, W * 0.06f, base, labels)
        if (dash.source == Dash.Source.SIM) {
            // made-up data: say so on every page
            val x = W * 0.06f + labels.measureText(title) + W * 0.025f
            labels.textSize = H * 0.020f
            val w = labels.measureText("SIM") + W * 0.03f
            rect.set(x, base - H * 0.024f, x + w, base + H * 0.002f)
            fill.color = orange
            c.drawRoundRect(rect, H * 0.006f, H * 0.006f, fill)
            labels.color = Color.BLACK
            c.drawText("SIM", x + W * 0.015f, base - H * 0.004f, labels)
        }
        labels.isFakeBoldText = false

        if (centre != null) {
            drawPump(c, W * 0.40f, base - H * 0.016f, H * 0.026f, centreCol)
            digits.textAlign = Paint.Align.LEFT
            digits.textSize = H * 0.030f
            digits.color = centreCol
            c.drawText(centre, W * 0.40f + H * 0.030f, base, digits)
        }

        digits.textAlign = Paint.Align.RIGHT
        digits.textSize = H * 0.060f
        digits.color = text
        c.drawText(clockText(), W * 0.94f, base + H * 0.008f, digits)

        fill.shader = LinearGradient(W * 0.04f, 0f, W * 0.96f, 0f,
            intArrayOf(Color.TRANSPARENT, orange, orange, Color.TRANSPARENT),
            floatArrayOf(0f, 0.15f, 0.85f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(W * 0.04f, H * 0.074f, W * 0.96f, H * 0.074f + 4f, fill)
        fill.shader = null
    }

    /** A value tile: small grey label, big white value, small unit. */
    private fun drawTile(c: Canvas, x: Float, y: Float, w: Float, h: Float, name: String, value: String, unit: String = "", col: Int = text) {
        rect.set(x, y, x + w, y + h)
        fill.color = panel
        c.drawRoundRect(rect, h * 0.10f, h * 0.10f, fill)
        stroke.color = Color.argb(90, 120, 140, 145)
        stroke.strokeWidth = 2f
        c.drawRoundRect(rect, h * 0.10f, h * 0.10f, stroke)
        labels.textAlign = Paint.Align.LEFT
        labels.textSize = h * 0.17f
        labels.color = label
        c.drawText(name, x + w * 0.07f, y + h * 0.27f, labels)
        digits.textAlign = Paint.Align.LEFT
        digits.textSize = h * 0.46f
        digits.color = col
        val vx = x + w * 0.07f
        val vy = y + h * 0.80f
        c.drawText(value, vx, vy, digits)
        if (unit.isNotEmpty()) {
            val vw = digits.measureText(value)
            labels.textSize = h * 0.18f
            labels.color = label
            c.drawText(unit, vx + vw + w * 0.03f, vy, labels)
        }
    }

    /**
     * Ultra-fine continuous gauge. The track carries the colour scale end to end (dim), the
     * filled part shows it at full strength, and a white needle marks the value.
     */
    private fun drawFineBar(c: Canvas, left: Float, right: Float, y: Float, bh: Float, frac: Float?,
                            cols: IntArray, stops: FloatArray) {
        val shader = LinearGradient(left, 0f, right, 0f, cols, stops, Shader.TileMode.CLAMP)
        val r = bh / 2f
        rect.set(left, y, right, y + bh)
        fill.shader = shader
        fill.alpha = 60
        c.drawRoundRect(rect, r, r, fill)
        fill.alpha = 255
        // quarter ticks under the track
        stroke.color = segOffEdge
        stroke.strokeWidth = 2f
        for (q in 0..4) {
            val x = left + (right - left) * q / 4f
            c.drawLine(x, y + bh + H * 0.003f, x, y + bh + H * 0.007f, stroke)
        }
        if (frac != null) {
            val f = frac.coerceIn(0f, 1f)
            val x = left + (right - left) * f
            if (f > 0f) {
                rect.set(left, y, x, y + bh)
                c.drawRoundRect(rect, r, r, fill)
            }
            fill.shader = null
            fill.color = text
            rect.set(x - 3f, y - bh * 0.9f, x + 3f, y + bh * 1.9f)
            c.drawRoundRect(rect, 3f, 3f, fill)
        }
        fill.shader = null
    }

    /** Red at empty, amber where the low-range warnings start, green above. */
    private fun fuelBar(c: Canvas, left: Float, right: Float, y: Float, bh: Float) {
        val f = dash.fuel
        // where the range warnings fall on the tank, from the current km per litre
        val fullKm = if (f.litres > 0.5f && f.rangeKm > 1f) f.rangeKm / f.litres * f.capacity else 0f
        val redF = DashConfig.FUEL_RED_FRACTION
        val ambF = if (fullKm > 0f) (DashConfig.FUEL_LOW_AMBER_KM / fullKm).coerceIn(redF + 0.05f, 0.6f) else 0.25f
        drawFineBar(c, left, right, y, bh, f.litres / f.capacity,
            intArrayOf(red, red, amber, amber, green, green),
            floatArrayOf(0f, redF * 0.8f, redF * 1.15f, ambF * 0.9f, ambF * 1.15f, 1f))
    }

    /** 40 to 120 °C: blue while cold, amber when warm, red when hot. */
    private fun coolantBar(c: Canvas, left: Float, right: Float, y: Float, bh: Float, coolC: Float?) {
        fun at(cDeg: Float) = ((cDeg - 40f) / 80f).coerceIn(0f, 1f)
        drawFineBar(c, left, right, y, bh, coolC?.let { at(it) },
            intArrayOf(blue, blue, amber, amber, red, red),
            floatArrayOf(0f, at(DashConfig.COOLANT_COLD_C) * 0.85f, at(DashConfig.COOLANT_COLD_C + 15f),
                at(DashConfig.COOLANT_HOT_C - 5f), at(DashConfig.COOLANT_WARN_C), 1f))
    }

    private fun f0(v: Float) = String.format(Locale.US, "%.0f", v)
    private fun f1(v: Float) = String.format(Locale.US, "%.1f", v)
    private fun hm(seconds: Float): String {
        val m = (seconds / 60).toInt()
        return String.format(Locale.US, "%d:%02d", m / 60, m % 60)
    }

    private fun rangeColour(): Int {
        return when (dash.fuel.warn) {
            FuelModel.Warn.FLASH -> if (flashOn()) red else faint
            FuelModel.Warn.RED -> red
            FuelModel.Warn.AMBER -> amber
            FuelModel.Warn.NONE -> text
        }
    }

    // ================================================================== Main
    private fun drawMain(c: Canvas) {
        drawHeader(c, if (t.has(Telem.FLAG_MAP2)) "MAP 2" else "MAP 1")
        drawTacho(c)
        drawChips(c)
        drawGear(c)
        drawCoolantGauge(c)
        drawFuelGauge(c)
        drawBattery(c)
        if (dash.showThrottle) drawThrottle(c)
        drawSpeed(c)
        drawFooter(c)
    }

    private fun drawTacho(c: Canvas) {
        val w = W; val h = H
        val x0 = w * 0.07f
        val top = h * 0.115f
        val bottom = h * 0.66f
        val wBottom = w * 0.075f
        val wTop = w * 0.17f
        val segs = DashConfig.RPM_MAX / 1000
        val gap = h * 0.005f
        val segH = (bottom - top - gap * (segs - 1)) / segs
        val rpmToY = { rpm: Float -> bottom - (bottom - top) * (rpm / DashConfig.RPM_MAX).coerceIn(0f, 1f) }
        val widthAt = { y: Float -> wBottom + (wTop - wBottom) * ((bottom - y) / (bottom - top)) }
        val logic = dash.logic
        val flashUp = logic.shiftUp && shiftFlashOn()
        val flashDown = logic.shiftDown && shiftFlashOn()
        val litY = if (dash.canOk) rpmToY(logic.tachoRpm) else bottom

        if (dash.canOk && (flashUp || flashDown)) {
            fill.color = if (flashDown) Color.argb(110, 30, 70, 200) else Color.argb(120, 200, 20, 20)
            rect.set(x0 - w * 0.03f, top - h * 0.01f, x0 + wTop + w * 0.03f, bottom + h * 0.01f)
            c.drawRoundRect(rect, w * 0.02f, w * 0.02f, fill)
        }
        stroke.strokeWidth = 3f
        for (i in 0 until segs) {
            val sb = bottom - i * (segH + gap)
            val st = sb - segH
            path.reset()
            path.moveTo(x0, sb); path.lineTo(x0 + widthAt(sb), sb); path.lineTo(x0 + widthAt(st), st); path.lineTo(x0, st); path.close()
            val segRpm = (i + 0.5f) * 1000f
            val (onCol, offCol) = when {
                segRpm >= DashConfig.SHIFT_RPM -> red to redOff
                segRpm >= DashConfig.RPM_AMBER -> amber to amberOff
                else -> blue to blueOff
            }
            fill.color = offCol
            c.drawPath(path, fill)
            if (litY < sb) {
                c.save()
                c.clipRect(x0 - 2f, max(litY, st), x0 + wTop + 2f, sb)
                fill.color = if (flashUp || flashDown) Color.WHITE else onCol
                c.drawPath(path, fill)
                c.restore()
            }
            stroke.color = if (litY < sb) blueEdge else segOffEdge
            c.drawPath(path, stroke)
        }
        if (dash.canOk && DashConfig.TACHO_PEAK_HOLD_MS > 0 && logic.peakRpm > logic.tachoRpm + 150) {
            val py = rpmToY(logic.peakRpm)
            stroke.color = orange
            stroke.strokeWidth = 7f
            c.drawLine(x0 - 6f, py, x0 + widthAt(py) + 10f, py, stroke)
        }
        digits.textAlign = Paint.Align.LEFT
        digits.textSize = h * 0.050f
        for (k in 0..segs) {
            val y = rpmToY(k * 1000f)
            digits.color = if (k * 1000 >= DashConfig.SHIFT_RPM + 500) red else label
            c.drawText(k.toString(), x0 + widthAt(y) + w * 0.025f, y + digits.textSize * 0.35f, digits)
        }
        labels.textAlign = Paint.Align.LEFT
        labels.textSize = h * 0.016f
        labels.color = label
        c.drawText("x1000 r/min", x0, top - h * 0.012f, labels)
        digits.textSize = h * 0.050f
        digits.color = canCol(text)
        c.drawText(if (dash.canOk) t.rpm.toString() else "----", x0, bottom + h * 0.063f, digits)
        labels.textSize = h * 0.017f
        labels.color = label
        c.drawText("r/min", x0, bottom + h * 0.080f, labels)
    }

    private fun drawChips(c: Canvas) {
        val chips = listOf(
            Triple("CLUTCH", dash.canOk && t.has(Telem.FLAG_CLUTCH_IN), false),
            // no STAND chip: this bike's side stand switch is bypassed, so it always reads up
            Triple("KILL", dash.canOk && !t.has(Telem.FLAG_KILL_RUN), true),
            Triple("CAN", alive && t.has(Telem.FLAG_CAN_OK), false),
        )
        val left = W * 0.42f
        val right = W * 0.94f
        val gap = W * 0.015f
        val cw = (right - left - gap * (chips.size - 1)) / chips.size
        val top = H * 0.095f
        val ch = H * 0.026f
        labels.textAlign = Paint.Align.CENTER
        labels.isFakeBoldText = true
        labels.textSize = ch * 0.62f
        val widest = chips.maxOf { labels.measureText(it.first) }
        if (widest > cw * 0.86f) labels.textSize *= cw * 0.86f / widest
        stroke.strokeWidth = 2.5f
        for ((i, chip) in chips.withIndex()) {
            val x = left + i * (cw + gap)
            rect.set(x, top, x + cw, top + ch)
            if (chip.second) {
                fill.color = if (chip.third) amber else text
                c.drawRoundRect(rect, ch * 0.2f, ch * 0.2f, fill)
                labels.color = Color.BLACK
            } else {
                stroke.color = faint
                c.drawRoundRect(rect, ch * 0.2f, ch * 0.2f, stroke)
                labels.color = faint
            }
            c.drawText(chip.first, x + cw / 2f, top + ch * 0.72f, labels)
        }
        labels.isFakeBoldText = false
    }

    private fun drawGear(c: Canvas) {
        val size = min(W * 0.30f, H * 0.142f)      // held to the height too, so it never reaches the coolant row
        val right = W * 0.94f
        val top = H * 0.145f
        rect.set(right - size, top, right, top + size * 1.12f)
        fill.color = gearBlock
        c.drawRoundRect(rect, size * 0.08f, size * 0.08f, fill)
        stroke.color = Color.rgb(90, 104, 108)
        stroke.strokeWidth = 3f
        c.drawRoundRect(rect, size * 0.08f, size * 0.08f, stroke)
        val g = when { !dash.canOk || t.gear == Telem.GEAR_UNKNOWN -> "-"; t.gear == 0 -> "N"; else -> t.gear.toString() }
        digits.textAlign = Paint.Align.CENTER
        digits.textSize = size
        digits.color = canCol(if (g == "N") green else yellow)
        c.drawText(g, rect.centerX(), rect.centerY() + digits.textSize * 0.36f, digits)
        labels.textAlign = Paint.Align.RIGHT
        labels.textSize = H * 0.016f
        labels.color = label
        c.drawText("GEAR", rect.left - W * 0.02f, rect.bottom - H * 0.005f, labels)
        val ax = rect.left - W * 0.08f
        if (dash.canOk && dash.logic.shiftUp) drawArrow(c, ax, rect.top + rect.height() * 0.30f, W * 0.045f, true, red)
        if (dash.canOk && dash.logic.shiftDown) drawArrow(c, ax, rect.top + rect.height() * 0.62f, W * 0.045f, false, blue)
    }

    /** Main page: the coolant, fuel and battery icons share this centre line. */
    private val iconCx get() = W * 0.435f

    private fun drawCoolantGauge(c: Canvas) {
        val left = W * 0.50f
        val right = W * 0.94f
        val y = H * 0.370f
        val bh = H * 0.016f
        val coolC = if (dash.canOk) t.coolantC else null
        val col = canCol(mainColour(dash.logic.coolant))
        // icon on the shared centre line, level with the middle of its bar
        val ts = H * 0.034f
        drawThermometer(c, iconCx - ts * 0.59f, y + bh * 0.5f, ts, col)
        digits.textAlign = Paint.Align.RIGHT
        digits.textSize = H * 0.042f
        digits.color = if (coolC == null) faint else col
        c.drawText(coolC?.let { "${f0(it)}°C" } ?: "--°C", right, y - H * 0.020f, digits)
        coolantBar(c, left, right, y + bh * 0.1f, bh * 0.8f, coolC)
        labels.textAlign = Paint.Align.CENTER
        labels.textSize = H * 0.016f
        labels.color = label
        c.drawText("C", left, y + bh + H * 0.020f, labels)
        c.drawText("H", right, y + bh + H * 0.020f, labels)
    }

    /** Fuel level and range, E to F, like the coolant gauge above it. */
    private fun drawFuelGauge(c: Canvas) {
        val f = dash.fuel
        val left = W * 0.50f
        val right = W * 0.94f
        val y = H * 0.487f
        val bh = H * 0.016f
        val rc = rangeColour()
        val ps = H * 0.032f
        drawPump(c, iconCx - ps * 0.42f, y + bh * 0.5f, ps, rc)
        digits.textAlign = Paint.Align.RIGHT
        digits.textSize = H * 0.042f
        digits.color = rc
        val range = "${f0(f.rangeKm)} km"
        c.drawText(range, right, y - H * 0.020f, digits)
        val rw = digits.measureText(range)
        digits.textSize = H * 0.026f
        digits.color = if (rc == text) text else label
        c.drawText("${f1(f.litres)} L", right - rw - W * 0.04f, y - H * 0.020f, digits)
        fuelBar(c, left, right, y + bh * 0.1f, bh * 0.8f)
        labels.textAlign = Paint.Align.CENTER
        labels.textSize = H * 0.016f
        labels.color = label
        c.drawText("E", left, y + bh + H * 0.020f, labels)
        c.drawText("F", right, y + bh + H * 0.020f, labels)
    }

    private fun drawBattery(c: Canvas) {
        val y = H * 0.575f
        val bs = H * 0.030f
        drawBatteryIcon(c, iconCx - bs * 0.65f, y, bs, mainColour(dash.logic.battery))
        digits.textAlign = Paint.Align.RIGHT
        digits.textSize = H * 0.042f
        digits.color = text
        c.drawText(if (dash.vbatShown > 0) "${f1(dash.vbatShown)} V" else "--.- V", W * 0.94f, y + H * 0.012f, digits)
    }

    private fun drawThrottle(c: Canvas) {
        val left = W * 0.42f
        val right = W * 0.94f
        val y = H * 0.665f
        val bh = H * 0.022f
        val pct = if (dash.canOk) t.throttle * 100f / 255f else 0f
        labels.textAlign = Paint.Align.LEFT
        labels.textSize = H * 0.018f
        labels.color = label
        c.drawText("THROTTLE", left, y - H * 0.020f, labels)
        digits.textAlign = Paint.Align.RIGHT
        digits.textSize = H * 0.042f
        digits.color = canCol(text)
        c.drawText(if (dash.canOk) "${f0(pct)}%" else "--%", right, y - H * 0.016f, digits)
        rect.set(left, y, right, y + bh)
        fill.color = segOff
        c.drawRoundRect(rect, bh * 0.3f, bh * 0.3f, fill)
        if (pct > 0.5f) {
            rect.set(left, y, left + (right - left) * pct / 100f, y + bh)
            fill.color = sage
            c.drawRoundRect(rect, bh * 0.3f, bh * 0.3f, fill)
        }
        rect.set(left, y, right, y + bh)
        stroke.color = segOffEdge
        stroke.strokeWidth = 2f
        c.drawRoundRect(rect, bh * 0.3f, bh * 0.3f, stroke)
        for (q in 1..3) {
            val x = left + (right - left) * q / 4f
            c.drawLine(x, y + bh, x, y + bh + H * 0.006f, stroke)
        }
    }

    private fun drawSpeed(c: Canvas) {
        stroke.color = faint
        stroke.strokeWidth = 2f
        c.drawLine(W * 0.06f, H * 0.745f, W * 0.94f, H * 0.745f, stroke)
        val spd = dash.logic.speedKmh
        digits.textAlign = Paint.Align.RIGHT
        digits.textSize = H * 0.155f
        digits.color = if (dash.logic.speedFromGps) text else canCol(text)      // wheel speed comes from CAN
        val baseY = H * 0.885f
        val right = W * 0.80f
        c.drawText(spd?.let { f0(it) } ?: "--", right, baseY, digits)
        labels.textAlign = Paint.Align.LEFT
        labels.textSize = H * 0.026f
        labels.color = text
        c.drawText("km/h", right + W * 0.02f, baseY, labels)
        if (dash.logic.speedFromGps) {
            labels.textSize = H * 0.016f
            labels.color = label
            c.drawText("GPS", right + W * 0.02f, baseY - H * 0.040f, labels)
        }
    }

    private fun drawFooter(c: Canvas) {
        stroke.color = faint
        stroke.strokeWidth = 2f
        c.drawLine(W * 0.06f, H * 0.915f, W * 0.94f, H * 0.915f, stroke)
        val y = H * 0.945f
        labels.textSize = H * 0.018f
        labels.textAlign = Paint.Align.LEFT
        labels.color = label
        val src = if (dash.source == Dash.Source.SIM) "SIM" else "BLE"
        c.drawText("$src  ${f0(dash.parser.stats.framesPerSec)} fps", W * 0.06f, y, labels)
        labels.textAlign = Paint.Align.RIGHT
        c.drawText(if (dash.gps.fix) "GPS  ${dash.gps.sats} sats" else "GPS  no fix", W * 0.94f, y, labels)    }

    private fun drawChevron(c: Canvas, cx: Float, cy: Float) {
        stroke.color = orange
        stroke.strokeWidth = 5f
        c.drawLine(cx - W * 0.10f, cy - H * 0.004f, cx - W * 0.02f, cy - H * 0.004f, stroke)
        c.drawLine(cx + W * 0.02f, cy - H * 0.004f, cx + W * 0.10f, cy - H * 0.004f, stroke)
        c.drawLine(cx - W * 0.02f, cy - H * 0.004f, cx, cy + H * 0.006f, stroke)
        c.drawLine(cx, cy + H * 0.006f, cx + W * 0.02f, cy - H * 0.004f, stroke)
    }

    // ================================================================== Split-screen strip
    // The ESP32 dash's main screen, laid out for the bottom 30% under a map app:
    // horizontal tacho with the rpm above its short end, then gear | coolant or battery
    // over the clock | road speed. Same colours as the full dash.
    private fun drawCompact(c: Canvas) {
        val logic = dash.logic
        val live = alive
        val flashUp = logic.shiftUp && shiftFlashOn()
        val flashDown = logic.shiftDown && shiftFlashOn()

        // ---- orange rule along the top edge, under the divider ----
        fill.shader = LinearGradient(W * 0.02f, 0f, W * 0.98f, 0f,
            intArrayOf(Color.TRANSPARENT, orange, orange, Color.TRANSPARENT),
            floatArrayOf(0f, 0.15f, 0.85f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(W * 0.02f, 0f, W * 0.98f, 4f, fill)
        fill.shader = null

        // ---- tacho: segments grow taller towards the redline ----
        val left = W * 0.03f
        val right = W * 0.97f
        val barBottom = H * 0.36f
        val barTop = H * 0.05f
        val segs = 36
        val gap = W * 0.004f
        val segW = (right - left - gap * (segs - 1)) / segs
        if (dash.canOk && (flashUp || flashDown)) {
            fill.color = if (flashDown) Color.argb(110, 30, 70, 200) else Color.argb(120, 200, 20, 20)
            rect.set(left - W * 0.01f, barTop - H * 0.02f, right + W * 0.01f, barBottom + H * 0.02f)
            c.drawRoundRect(rect, W * 0.01f, W * 0.01f, fill)
        }
        val litF = if (dash.canOk) logic.tachoRpm / DashConfig.RPM_MAX * segs else 0f
        for (i in 0 until segs) {
            val sx = left + i * (segW + gap)
            val segRpm = (i + 1) * DashConfig.RPM_MAX.toFloat() / segs
            val (onCol, offCol) = when {
                segRpm > DashConfig.SHIFT_RPM -> red to redOff
                segRpm > DashConfig.RPM_AMBER -> amber to amberOff
                else -> blue to blueOff
            }
            val tq = i.toFloat() / (segs - 1)
            val sh = (barBottom - barTop) * (0.14f + 0.86f * tq * tq)
            fill.color = offCol
            c.drawRect(sx, barBottom - sh, sx + segW, barBottom, fill)
            val litFrac = (litF - i).coerceIn(0f, 1f)
            if (litFrac > 0.05f) {
                fill.color = if (flashUp || flashDown) Color.WHITE else onCol
                c.drawRect(sx, barBottom - sh * litFrac, sx + segW, barBottom, fill)
            }
        }
        if (dash.canOk && DashConfig.TACHO_PEAK_HOLD_MS > 0 && logic.peakRpm > logic.tachoRpm + 150) {
            val px = left + (right - left) * (logic.peakRpm / DashConfig.RPM_MAX).coerceIn(0f, 1f)
            fill.color = orange
            c.drawRect(px - 3f, barTop, px + 3f, barBottom, fill)
        }
        // scale 1..9, shift point in red
        digits.textAlign = Paint.Align.CENTER
        digits.textSize = H * 0.075f
        for (k in 1..DashConfig.RPM_MAX / 1000) {
            val x = left + (right - left) * k * 1000f / DashConfig.RPM_MAX
            digits.color = if (k * 1000 > DashConfig.SHIFT_RPM) red else label
            c.drawText(k.toString(), min(x, right - digits.textSize * 0.3f), barBottom + H * 0.085f, digits)
        }
        stroke.color = red
        stroke.strokeWidth = 4f
        val shx = left + (right - left) * DashConfig.SHIFT_RPM / DashConfig.RPM_MAX
        c.drawLine(shx, barBottom + H * 0.01f, shx, barBottom + H * 0.035f, stroke)
        // rpm digits over the short end of the bar
        digits.textAlign = Paint.Align.LEFT
        digits.textSize = H * 0.23f
        digits.color = canCol(text)
        val rpmBase = barTop + H * 0.20f                 // clear of the short segments under it
        c.drawText(if (dash.canOk) t.rpm.toString() else "----", left, rpmBase, digits)
        labels.textAlign = Paint.Align.LEFT
        labels.textSize = H * 0.055f
        labels.color = label
        c.drawText("r/min", left + digits.measureText("8888") + W * 0.015f, rpmBase, labels)

        // ---- panels: gear | coolant or battery over the clock | speed ----
        val top = H * 0.50f
        val bottom = H * 0.97f
        val pg = W * 0.015f
        val gearR = W * 0.28f
        val midR = gearR + pg + W * 0.30f
        drawCompactGear(c, W * 0.03f, top, gearR, bottom)
        val rowH = (bottom - top - pg) / 2f
        val x1 = gearR + pg
        if (logic.coolant == Level.BAD || logic.battery == Level.BAD) {
            drawCompactValue(c, x1, top, midR, top + rowH, true)
            drawCompactValue(c, x1, bottom - rowH, midR, bottom, false)
        } else {
            val coolantTurn = ((SystemClock.elapsedRealtime() / DashConfig.VALUE_CYCLE_MS) % 2 == 0L) != dash.compactSwap
            drawCompactValue(c, x1, top, midR, top + rowH, coolantTurn)
            panel(c, x1, bottom - rowH, midR, bottom)
            digits.textAlign = Paint.Align.CENTER
            compactRowText(rowH, midR - x1, "88:88")
            digits.color = text
            c.drawText(clockText(), (x1 + midR) / 2f, bottom - rowH / 2f + digits.textSize * 0.36f, digits)
        }
        val sx0 = midR + pg
        val sx1 = W * 0.97f
        panel(c, sx0, top, sx1, bottom)
        val ph = bottom - top
        digits.textAlign = Paint.Align.RIGHT
        digits.textSize = ph * 0.78f
        val spdText = logic.speedKmh?.let { f0(it) } ?: "--"
        val spdRoom = (sx1 - sx0) - W * 0.05f
        val spdW = digits.measureText("888")
        if (spdW > spdRoom) digits.textSize *= spdRoom / spdW
        digits.color = if (logic.speedFromGps) text else canCol(text)
        c.drawText(spdText, sx1 - W * 0.025f, (top + bottom) / 2f + digits.textSize * 0.36f, digits)

        // touch: speed panel = other map app, no label (swipe left/right does the same; swipe up for the full dash)
        buttons.add(Btn(RectF(sx0, top, sx1, bottom)) { dash.swapMapApp() })

        if (!live) drawNoLink(c, "NO LINK", H * 0.21f, H * 0.24f)
        else if (!dash.canOk) drawNoLink(c, "NO CAN DATA", H * 0.21f, H * 0.24f)
    }

    private fun smallChip(c: Canvas, x: Float, y: Float, h: Float, s: String, alignRight: Boolean = false) {
        labels.textAlign = Paint.Align.LEFT
        labels.isFakeBoldText = true
        labels.textSize = h * 0.62f
        val w = labels.measureText(s) + h * 0.8f
        val l = if (alignRight) x - w else x
        rect.set(l, y, l + w, y + h)
        fill.color = Color.argb(150, 120, 140, 145)
        c.drawRoundRect(rect, h * 0.3f, h * 0.3f, fill)
        labels.color = Color.BLACK
        c.drawText(s, l + h * 0.4f, y + h * 0.72f, labels)
        labels.isFakeBoldText = false
    }

    private fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        rect.set(l, t, r, b)
        fill.color = panel
        c.drawRoundRect(rect, W * 0.015f, W * 0.015f, fill)
        stroke.color = Color.argb(90, 120, 140, 145)
        stroke.strokeWidth = 2f
        c.drawRoundRect(rect, W * 0.015f, W * 0.015f, stroke)
    }

    private fun drawCompactGear(c: Canvas, l: Float, top: Float, r: Float, b: Float) {
        rect.set(l, top, r, b)
        fill.color = gearBlock
        c.drawRoundRect(rect, W * 0.02f, W * 0.02f, fill)
        stroke.color = Color.rgb(90, 104, 108)
        stroke.strokeWidth = 3f
        c.drawRoundRect(rect, W * 0.02f, W * 0.02f, stroke)
        val cx = (l + r) / 2f
        val cy = (top + b) / 2f
        val h = b - top
        val g = when { !dash.canOk || t.gear == Telem.GEAR_UNKNOWN -> "-"; t.gear == 0 -> "N"; else -> t.gear.toString() }
        digits.textAlign = Paint.Align.CENTER
        digits.textSize = h * 0.92f
        digits.color = canCol(if (g == "N") green else yellow)
        c.drawText(g, cx, cy + digits.textSize * 0.36f, digits)
        val ax = r - (r - l) * 0.14f
        if (dash.canOk && dash.logic.shiftUp && shiftFlashOn()) drawArrow(c, ax, top + h * 0.30f, h * 0.08f, true, red)
        if (dash.canOk && dash.logic.shiftDown && shiftFlashOn()) drawArrow(c, ax, b - h * 0.20f, h * 0.08f, false, blue)
    }

    /** Split strip: big digits for one half-height panel, held to the width of [widest]. */
    private fun compactRowText(rowH: Float, w: Float, widest: String) {
        digits.textSize = rowH * 0.84f
        val room = w * 0.92f
        val tw = digits.measureText(widest)
        if (tw > room) digits.textSize *= room / tw
    }

    /** Coolant or battery, taking turns: the value alone in the status colour, both at one size. */
    private fun drawCompactValue(c: Canvas, l: Float, top: Float, r: Float, b: Float, coolant: Boolean) {
        panel(c, l, top, r, b)
        val h = b - top
        val cy = (top + b) / 2f
        val col: Int
        val value: String
        if (coolant) {
            col = canCol(levelColour(dash.logic.coolant))
            value = (if (dash.canOk) t.coolantC else null)?.let { "${f0(it)}°" } ?: "--°"
        } else {
            col = levelColour(dash.logic.battery)
            value = if (dash.vbatShown > 0) "${f1(dash.vbatShown)}V" else "--.-V"
        }
        // sized to the widest voltage, so the coolant reading is the same height
        compactRowText(h, r - l, "88.8V")
        digits.color = col
        digits.textAlign = Paint.Align.CENTER
        c.drawText(value, (l + r) / 2f, cy + digits.textSize * 0.36f, digits)
    }

    // ================================================================== Fuel
    private fun drawFuel(c: Canvas) {
        val f = dash.fuel
        drawHeader(c, "FUEL")
        // range
        val rc = rangeColour()
        drawPump(c, W * 0.07f, H * 0.130f, H * 0.045f, rc)
        labels.textAlign = Paint.Align.LEFT
        labels.textSize = H * 0.020f
        labels.color = label
        c.drawText("RANGE", W * 0.07f + H * 0.06f, H * 0.120f, labels)
        digits.textAlign = Paint.Align.RIGHT
        digits.textSize = H * 0.13f
        digits.color = rc
        c.drawText(f0(f.rangeKm), W * 0.78f, H * 0.250f, digits)
        labels.textSize = H * 0.030f
        labels.color = text
        c.drawText("km", W * 0.80f, H * 0.250f, labels)

        // tank gauge
        val left = W * 0.06f
        val right = W * 0.94f
        val y = H * 0.315f
        fuelBar(c, left, right, y + H * 0.012f, H * 0.010f)
        labels.textAlign = Paint.Align.CENTER
        labels.textSize = H * 0.020f
        labels.color = label
        c.drawText("E", left, y + H * 0.065f, labels)
        c.drawText("F", right, y + H * 0.065f, labels)
        digits.textAlign = Paint.Align.CENTER
        digits.textSize = H * 0.034f
        digits.color = text
        c.drawText("${f1(f.litres)} L of ${f1(f.capacity)} L", W / 2f, y + H * 0.068f, digits)

        // tiles
        val tw = W * 0.425f
        val th = H * 0.115f
        val x1 = W * 0.06f
        val x2 = W * 0.515f
        var ty = H * 0.425f
        drawTile(c, x1, ty, tw, th, "AVG SINCE FILL", if (f.avgL100 > 0) f1(f.avgL100) else "--", "L/100")
        val stopped = dash.speedKmh < 5f
        drawTile(c, x2, ty, tw, th, "NOW", if (stopped) f1(f.rateLph) else if (f.instL100 > 0) f1(f.instL100) else "--",
            if (stopped) "L/h" else "L/100")
        ty += th + H * 0.02f
        drawTile(c, x1, ty, tw, th, "USED SINCE FILL", f1(f.usedSinceFill), "L")
        drawTile(c, x2, ty, tw, th, "SINCE FILL", f0(f.kmSinceFill), "km")

        labels.textAlign = Paint.Align.LEFT
        labels.textSize = H * 0.019f
        labels.color = label
        c.drawText("Learned factor x${String.format(Locale.US, "%.2f", f.kRatio)} from ${f.learnedFills} full fill-ups", W * 0.06f, H * 0.72f, labels)
        c.drawText("No fuel sensor: estimated from rpm and throttle.", W * 0.06f, H * 0.75f, labels)
        button(c, W * 0.18f, H * 0.80f, W * 0.82f, H * 0.865f, "FILL UP", primary = true) { dash.openFillUp() }
    }

    private fun drawFuelEditor(c: Canvas) {
        val f = dash.fuel
        val r = drawSheet(c, "FILL-UP", emptyList())
        val l = r.left + r.width() * 0.05f
        val rr = r.right - r.width() * 0.05f
        val gap = r.width() * 0.04f
        val half = (rr - l - gap) / 2f
        // type
        var y = r.top + H * 0.075f
        val bh = H * 0.058f
        button(c, l, y, l + half, y + bh, "FULL TANK", selected = f.editFull) { f.editSetFull(true) }
        button(c, rr - half, y, rr, y + bh, "PARTIAL", selected = !f.editFull) { f.editSetFull(false) }
        // litres
        y += bh + H * 0.065f
        digits.textAlign = Paint.Align.CENTER
        digits.textSize = H * 0.075f
        digits.color = yellow
        c.drawText("${f1(f.editLitres)} L", r.centerX(), y, digits)
        labels.textAlign = Paint.Align.CENTER
        labels.textSize = H * 0.019f
        labels.color = label
        c.drawText(if (f.editFull) "pumped to fill the tank (0 = don't learn)" else if (f.editLitres < 0f) "taken out of the tank" else "added to the tank (minus = taken out)", r.centerX(), y + H * 0.032f, labels)
        // steppers (clear of CANCEL / SAVE below)
        y += H * 0.046f
        val q = (rr - l - gap * 3) / 4f
        val steps = listOf("−1" to -1f, "−0.5" to -0.5f, "+0.5" to 0.5f, "+1" to 1f)
        for ((i, st) in steps.withIndex()) {
            val x = l + i * (q + gap)
            button(c, x, y, x + q, y + bh, st.first) { f.editStepLitres(st.second) }
        }
        // save / cancel
        val by = r.bottom - H * 0.025f - bh
        button(c, l, by, l + half, by + bh, "CANCEL") { f.editCancel() }
        button(c, rr - half, by, rr, by + bh, "SAVE", primary = true) { dash.saveFillUp() }
    }

    // ================================================================== Trip
    private fun drawTrip(c: Canvas) {
        drawHeader(c, "TRIP")
        drawTripSet(c, "TODAY", dash.trip.today, H * 0.095f)
        drawTripSet(c, "SINCE REFUEL", dash.trip.sinceRefuel, H * 0.505f)
    }

    private fun drawTripSet(c: Canvas, title: String, s: TripSet, top: Float) {
        labels.textAlign = Paint.Align.LEFT
        labels.textSize = H * 0.024f
        labels.isFakeBoldText = true
        labels.color = orange
        c.drawText(title, W * 0.06f, top + H * 0.030f, labels)
        labels.isFakeBoldText = false
        digits.textAlign = Paint.Align.RIGHT
        digits.textSize = H * 0.10f
        digits.color = text
        c.drawText(f1(s.km), W * 0.80f, top + H * 0.125f, digits)
        labels.textSize = H * 0.028f
        labels.color = text
        c.drawText("km", W * 0.82f, top + H * 0.125f, labels)
        val gap = W * 0.025f
        val tw = (W * 0.88f - gap * 2) / 3
        val th = H * 0.10f
        var y = top + H * 0.150f
        drawTile(c, W * 0.06f, y, tw, th, "FUEL", f1(s.fuelL), "L")
        drawTile(c, W * 0.06f + tw + gap, y, tw, th, "AVERAGE", if (s.avgL100 > 0) f1(s.avgL100) else "--", "L/100")
        drawTile(c, W * 0.06f + 2 * (tw + gap), y, tw, th, "RIDING", hm(s.movingS))
        y += th + H * 0.015f
        drawTile(c, W * 0.06f, y, tw, th, "AVG SPEED", if (s.avgKmh > 0) f0(s.avgKmh) else "--", "km/h")
        drawTile(c, W * 0.06f + tw + gap, y, tw, th, "MAX SPEED", f0(s.maxKmh), "km/h")
    }

    // ================================================================== Tyres
    private fun drawTyres(c: Canvas) {
        drawHeader(c, "TYRES")
        drawTyreTile(c, "FRONT", dash.tpms.front, DashConfig.TPMS_FRONT_LOW_BAR, H * 0.11f)
        drawTyreTile(c, "REAR", dash.tpms.rear, DashConfig.TPMS_REAR_LOW_BAR, H * 0.47f)
        button(c, W * 0.18f, H * 0.885f, W * 0.82f, H * 0.945f, "PAIR SENSORS") { dash.tpms.pairOpen() }
    }

    private fun drawTyreTile(c: Canvas, name: String, r: TyreReading, lowBar: Float, top: Float) {
        val h = H * 0.33f
        val low = r.valid && !r.stale && r.bar < lowBar
        rect.set(W * 0.06f, top, W * 0.94f, top + h)
        fill.color = if (low && flashOn()) Color.argb(200, 150, 20, 20) else panel
        c.drawRoundRect(rect, h * 0.06f, h * 0.06f, fill)
        stroke.color = if (low) red else Color.argb(90, 120, 140, 145)
        stroke.strokeWidth = if (low) 6f else 2f
        c.drawRoundRect(rect, h * 0.06f, h * 0.06f, stroke)
        labels.textAlign = Paint.Align.LEFT
        labels.textSize = H * 0.026f
        labels.isFakeBoldText = true
        labels.color = orange
        c.drawText(name, rect.left + W * 0.05f, top + H * 0.045f, labels)
        labels.isFakeBoldText = false
        labels.textAlign = Paint.Align.RIGHT
        labels.textSize = H * 0.018f
        labels.color = label
        if (r.paired) c.drawText("sensor ${r.tag}", rect.right - W * 0.05f, top + H * 0.045f, labels)

        when {
            !r.paired -> centreNote(c, "NOT PAIRED", "2 taps to pair", top + h * 0.55f)
            !r.valid -> centreNote(c, "WAITING", "no reading yet - spin the wheel", top + h * 0.55f)
            else -> {
                val col = when { r.stale -> faint; low -> if (flashOn()) yellow else text; else -> text }
                val value = if (DashConfig.TPMS_PSI) f0(r.bar * 14.5038f) else String.format(Locale.US, "%.2f", r.bar)
                digits.textAlign = Paint.Align.RIGHT
                digits.textSize = h * 0.48f
                digits.color = col
                c.drawText(value, W * 0.70f, top + h * 0.66f, digits)
                labels.textAlign = Paint.Align.LEFT
                labels.textSize = H * 0.030f
                labels.color = col
                c.drawText(if (DashConfig.TPMS_PSI) "psi" else "bar", W * 0.72f, top + h * 0.66f, labels)
                labels.textSize = H * 0.020f
                labels.color = if (r.stale) faint else label
                val age = if (r.ageS < 60) "${r.ageS} s ago" else "${r.ageS / 60} min ago"
                val batt = if (r.battV > 0) "  ·  cell ${f1(r.battV)} V" + (if (r.battV < DashConfig.TPMS_SENSOR_BATT_LOW_V) " LOW" else "") else ""
                c.drawText("${r.tempC}°C  ·  $age$batt", rect.left + W * 0.05f, top + h * 0.90f, labels)
            }
        }
    }

    private fun centreNote(c: Canvas, big: String, small: String, y: Float) {
        digits.textAlign = Paint.Align.CENTER
        digits.textSize = H * 0.045f
        digits.color = label
        c.drawText(big, W / 2f, y, digits)
        labels.textAlign = Paint.Align.CENTER
        labels.textSize = H * 0.020f
        labels.color = faint
        c.drawText(small, W / 2f, y + H * 0.040f, labels)
    }

    private fun drawPairing(c: Canvas) {
        val p = dash.tpms
        val r = drawSheet(c, if (p.pairStep == 0) "PAIR FRONT SENSOR" else "PAIR REAR SENSOR",
            listOf("Tap a sensor, then assign it"), tall = true)
        val seen = p.seen()
        val l = r.left + r.width() * 0.05f
        val rr = r.right - r.width() * 0.05f
        val gap = r.width() * 0.04f
        val third = (rr - l - gap * 2) / 3f
        val bh = H * 0.058f
        val by = r.bottom - H * 0.075f - bh
        button(c, l, by, l + third, by + bh, "CANCEL") { p.pairCancel() }
        button(c, l + third + gap, by, l + 2 * third + gap, by + bh, "SKIP") { p.pairSkip() }
        button(c, rr - third, by, rr, by + bh, if (p.pairStep == 0) "SET FRONT" else "SET REAR", primary = true,
            enabled = p.pairSel < seen.size) { p.pairAssign() }
        if (seen.isEmpty()) {
            labels.textAlign = Paint.Align.CENTER
            labels.textSize = H * 0.022f
            labels.color = label
            c.drawText("Listening for sensors… spin the wheel", r.centerX(), r.top + r.height() * 0.35f, labels)
            return
        }
        val rowH = r.height() * 0.11f
        var y = r.top + r.height() * 0.13f
        for ((i, s) in seen.withIndex()) {
            val sel = i == p.pairSel
            rect.set(r.left + W * 0.03f, y, r.right - W * 0.03f, y + rowH * 0.85f)
            fill.color = if (sel) sage else Color.argb(80, 255, 255, 255)
            c.drawRoundRect(rect, rowH * 0.15f, rowH * 0.15f, fill)
            mono.textSize = rowH * 0.36f
            mono.color = if (sel) Color.BLACK else text
            mono.textAlign = Paint.Align.LEFT
            c.drawText("${s.tag}  ${s.type.padEnd(6)} ${f0(s.bar * 14.5038f)} psi  ${s.rssi} dBm", rect.left + W * 0.03f, rect.centerY() + mono.textSize * 0.35f, mono)
            val idx = i
            buttons.add(Btn(RectF(rect)) { p.pairSelect(idx) })
            y += rowH
        }
    }

    // ================================================================== Map
    private fun drawMap(c: Canvas) {
        val nav = dash.nav
        drawHeader(c, "MAP")
        val top = H * 0.08f
        val bottom = H * 0.965f
        val cx = W / 2f
        val cy = (top + bottom) / 2f
        val pxPerM = (bottom - top) / 2f / nav.mapRadiusM

        c.save()
        c.clipRect(0f, top, W, bottom)
        // grid rings every quarter radius
        stroke.color = Color.argb(40, 160, 190, 190)
        stroke.strokeWidth = 2f
        for (k in 1..2) c.drawCircle(cx, cy, (bottom - top) / 2f * k / 2f, stroke)

        if (nav.hasPosition) {
            val me = nav.position
            fun toScreen(p: NavPoint): Pair<Float, Float> {
                val (x, y) = NavModel.offsetMeters(me, p)
                return (cx + x * pxPerM) to (cy - y * pxPerM)
            }
            // trail
            if (nav.trail.size >= 2) {
                path.reset()
                nav.trail.forEachIndexed { i, p ->
                    val (sx, sy) = toScreen(p)
                    if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                }
                val (lx, ly) = toScreen(me)
                path.lineTo(lx, ly)
                stroke.color = cyan
                stroke.strokeWidth = 8f
                c.drawPath(path, stroke)
            }
            // line to the target
            if (nav.navigating) {
                val (tx, ty) = toScreen(nav.targetPos)
                stroke.color = yellow
                stroke.strokeWidth = 4f
                stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 16f), 0f)
                c.drawLine(cx, cy, tx, ty, stroke)
                stroke.pathEffect = null
            }
            // waypoints
            for (wp in nav.waypoints) {
                val (sx, sy) = toScreen(wp.pos)
                val target = nav.navigating && !nav.tracBack && wp.id == nav.targetId
                fill.color = if (target) yellow else orange
                c.drawCircle(sx, sy, W * 0.030f, fill)
                if (wp.id == nav.selectedId) {
                    stroke.color = text; stroke.strokeWidth = 4f
                    c.drawCircle(sx, sy, W * 0.042f, stroke)
                }
                if (!nav.menuOpen) {
                    val hit = W * 0.065f                       // bigger than the dot, for a gloved finger
                    val id = wp.id
                    buttons.add(Btn(RectF(sx - hit, sy - hit, sx + hit, sy + hit), hold = { renameWaypoint(id) }) { openNavMenuAt(id) })
                }
                digits.textAlign = Paint.Align.CENTER
                digits.textSize = W * 0.034f
                digits.color = Color.BLACK
                c.drawText(wp.id.toString(), sx, sy + digits.textSize * 0.36f, digits)
                if (wp.label.isNotEmpty()) {
                    // the name beside the marker, with a dark edge so it reads over the trail
                    labels.textAlign = Paint.Align.LEFT
                    labels.isFakeBoldText = true
                    labels.textSize = W * 0.034f
                    labels.style = Paint.Style.STROKE; labels.strokeWidth = 6f; labels.color = Color.BLACK
                    c.drawText(wp.label, sx + W * 0.040f, sy + labels.textSize * 0.36f, labels)
                    labels.style = Paint.Style.FILL; labels.color = if (target) yellow else text
                    c.drawText(wp.label, sx + W * 0.040f, sy + labels.textSize * 0.36f, labels)
                    labels.isFakeBoldText = false
                }
            }
            // me: pointer when moving, dot when stopped
            val hdg = dash.headingDeg
            if (hdg != null) {
                c.save()
                c.rotate(hdg, cx, cy)
                path.reset()
                val s = W * 0.05f
                path.moveTo(cx, cy - s * 1.2f); path.lineTo(cx + s * 0.75f, cy + s * 0.8f)
                path.lineTo(cx, cy + s * 0.35f); path.lineTo(cx - s * 0.75f, cy + s * 0.8f); path.close()
                fill.color = text
                c.drawPath(path, fill)
                stroke.color = Color.BLACK
                stroke.strokeWidth = 3f
                c.drawPath(path, stroke)
                c.restore()
            } else {
                fill.color = text
                c.drawCircle(cx, cy, W * 0.022f, fill)
                stroke.color = Color.BLACK
                stroke.strokeWidth = 3f
                c.drawCircle(cx, cy, W * 0.022f, stroke)
            }
        } else {
            centreNote(c, "WAITING FOR GPS", if (dash.phoneGps?.enabled == false) "turn on Location" else "${dash.gps.sats} satellites", cy)
        }
        c.restore()

        // heading tape across the top, then latitude | longitude, then what3words | nav line
        drawHeadingTape(c, H * 0.080f, H * 0.215f)
        val tx = W * 0.05f
        val tw = W * 0.43f
        digits.color = if (nav.fix) text else faint
        fun fit(p: Paint, s: String, size: Float) { p.textSize = size; val w = p.measureText(s); if (w > tw) p.textSize = size * tw / w }
        val lat = if (nav.hasPosition) NavModel.formatDM(nav.position.latE7, true) else "S --° --.---′"
        val lon = if (nav.hasPosition) NavModel.formatDM(nav.position.lonE7, false) else "E ---° --.---′"
        fit(digits, lat, H * 0.042f)
        val ll = min(digits.textSize, run { fit(digits, lon, H * 0.042f); digits.textSize })   // same size for both
        digits.textSize = ll
        digits.textAlign = Paint.Align.LEFT
        c.drawText(lat, tx, H * 0.262f, digits)
        digits.textAlign = Paint.Align.RIGHT
        c.drawText(lon, W - tx, H * 0.262f, digits)
        drawWhat3Words(c, tx, H * 0.302f, W * 0.52f)
        labels.textAlign = Paint.Align.RIGHT
        labels.textSize = H * 0.020f
        labels.color = if (nav.navigating) yellow else label
        val navLine = when {
            nav.navigating && nav.arrived -> "ARRIVED"
            nav.navigating && nav.tracBack -> "TracBack  ${NavModel.formatDist(nav.targetDistM)}"
            nav.navigating -> (if (nav.targetId != nav.finalId) "${nav.nameOf(nav.targetId)} > ${nav.nameOf(nav.finalId)}" else nav.nameOf(nav.targetId)) + "  ${NavModel.formatDist(nav.targetDistM)}"
            else -> "${nav.waypoints.size} wpt  ·  ${dash.gps.sats} sats"
        }
        c.drawText(navLine, W - tx, H * 0.300f, labels)

        // buttons: zoom, drop, waypoint list
        val by = H * 0.875f
        val bh = H * 0.052f
        button(c, W * 0.04f, by, W * 0.15f, by + bh, "−") { nav.zoom(1) }
        button(c, W * 0.17f, by, W * 0.28f, by + bh, "+") { nav.zoom(-1) }
        button(c, W * 0.30f, by, W * 0.44f, by + bh, "AUTO", selected = nav.autoZoom) { nav.zoomAuto() }
        button(c, W * 0.47f, by, W * 0.69f, by + bh, "DROP WPT", primary = true) { nav.dropWaypoint() }
        button(c, W * 0.72f, by, W * 0.96f, by + bh, "WAYPOINTS") { openNavMenu() }

        // scale, bottom left; position, bottom right
        labels.textAlign = Paint.Align.LEFT
        labels.textSize = H * 0.020f
        labels.color = label
        c.drawText("↕ ${NavModel.formatDist(nav.mapRadiusM)}", W * 0.05f, H * 0.952f, labels)
        labels.textAlign = Paint.Align.RIGHT
        labels.color = faint
        c.drawText("pinch to zoom", W * 0.95f, H * 0.952f, labels)
    }

    /**
     * what3words: a red /// logo, then the three.word.address (grey when it's for somewhere else).
     * The API is only called when the logo is tapped; the logo dims while a lookup is on its way.
     */
    private fun drawWhat3Words(c: Canvas, x: Float, y: Float, maxW: Float) {
        val w = dash.w3w
        val nav = dash.nav
        val words = w?.words
        val here = words != null && nav.hasPosition && w.wordsAt?.let { NavModel.distM(it, nav.position) <= 3f } == true
        val body = when {
            w == null -> "what3words off"
            w.busy -> "looking up…"
            words != null -> words
            else -> w.status
        }

        // logo: red rounded square with white slashes
        val ls = H * 0.040f
        val top = y - ls * 0.80f
        rect.set(x, top, x + ls, top + ls)
        fill.color = if (w?.enabled == true && !w.busy) red else Color.rgb(110, 50, 50)
        c.drawRoundRect(rect, ls * 0.14f, ls * 0.14f, fill)
        labels.textAlign = Paint.Align.CENTER
        labels.isFakeBoldText = true
        labels.textSize = ls * 0.62f
        labels.color = Color.WHITE
        c.drawText("///", rect.centerX(), rect.centerY() + labels.textSize * 0.36f, labels)

        // address
        val tx = x + ls + W * 0.020f
        val room = maxW - (tx - x)
        labels.textAlign = Paint.Align.LEFT
        labels.textSize = H * 0.028f
        val tw = labels.measureText(body)
        if (tw > room) labels.textSize *= room / tw
        labels.color = if (words == null || w.busy) label else if (here) text else faint
        c.drawText(body, tx, y, labels)
        labels.isFakeBoldText = false

        // the logo is the button (with a little slack around it for a gloved finger)
        if (w != null && w.enabled && nav.hasPosition) {
            val pad = ls * 0.35f
            buttons.add(Btn(RectF(x - pad, top - pad, x + ls + pad, top + ls + pad)) { w.lookup(nav.position) })
        }
    }

    private val points16 = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    private fun point16(deg: Float) = points16[(((deg + 11.25f) % 360f + 360f) % 360f / 22.5f).toInt() % 16]

    /**
     * Heading tape, dive-computer style: the heading in big digits, and a strip of 5-degree
     * ticks with 8-point labels sliding under a fixed red centre line (±60° shown). While
     * navigating, a yellow triangle on the strip is the bearing to the next waypoint (pinned
     * to the edge when it's off the strip), and a ◀ / ▶ arrow by the heading says which way
     * to turn and by how many degrees, with the distance on the other side.
     */
    private fun drawHeadingTape(c: Canvas, top: Float, bottom: Float) {
        val nav = dash.nav
        val hdg = dash.headingDeg
        val heading = hdg ?: 0f
        val cx = W / 2f
        val l = W * 0.03f
        val r = W * 0.97f
        val span = 60f                                    // degrees each side of the centre
        val pxPerDeg = (r - l) / 2f / span
        val band = Color.rgb(9, 14, 17)
        fill.color = band
        c.drawRect(0f, top, W, bottom, fill)

        val digitBase = top + H * 0.060f
        val labelBase = top + H * 0.090f
        val lineY = top + H * 0.122f
        fun wrap(d: Float) = ((d % 360f) + 540f) % 360f - 180f    // -180..180
        fun xAt(deg: Float) = cx + wrap(deg - heading) * pxPerDeg

        // ticks and labels (only when there's a heading)
        if (hdg != null) {
            val first = (kotlin.math.floor((heading - span) / 5f) * 5).toInt()
            for (d in first..(first + (2 * span).toInt() + 10) step 5) {
                val x = xAt(d.toFloat())
                if (x < l || x > r) continue
                val dd = ((d % 360) + 360) % 360
                val major = dd % 10 == 0
                val len = when { dd % 45 == 0 -> H * 0.024f; major -> H * 0.018f; else -> H * 0.010f }
                stroke.color = if (dd % 45 == 0) text else label
                stroke.strokeWidth = if (major) 3.5f else 2f
                c.drawLine(x, lineY - len, x, lineY, stroke)
                if (dd % 45 == 0) {
                    labels.textAlign = Paint.Align.CENTER
                    labels.isFakeBoldText = true
                    labels.textSize = H * 0.024f
                    labels.color = if (dd == 0) red else text
                    c.drawText(points16[dd / 45 * 2], x, labelBase, labels)
                    labels.isFakeBoldText = false
                }
            }
        }

        // glowing blue line along the strip
        val glow = LinearGradient(l, 0f, r, 0f,
            intArrayOf(Color.TRANSPARENT, blue, blue, Color.TRANSPARENT), floatArrayOf(0f, 0.25f, 0.75f, 1f), Shader.TileMode.CLAMP)
        fill.shader = glow
        fill.alpha = 70
        c.drawRect(l, lineY - H * 0.002f, r, lineY + H * 0.008f, fill)
        fill.alpha = 255
        c.drawRect(l, lineY - H * 0.0015f, r, lineY + H * 0.0025f, fill)
        fill.shader = null

        // edges fade into the band
        for (left in listOf(true, false)) {
            val a = if (left) 0f else r - W * 0.12f
            val b = if (left) l + W * 0.12f else W
            fill.shader = LinearGradient(a, 0f, b, 0f, if (left) band else Color.TRANSPARENT, if (left) Color.TRANSPARENT else band, Shader.TileMode.CLAMP)
            c.drawRect(a, labelBase - H * 0.030f, b, lineY - H * 0.002f, fill)
        }
        fill.shader = null

        // waypoint bearing: yellow triangle on the line, pinned to the edge when off the strip
        val navTo = nav.navigating && !nav.arrived && hdg != null
        val turn = if (navTo) wrap(nav.targetBearingDeg - heading) else 0f
        if (navTo) {
            val off = kotlin.math.abs(turn) > span
            val x = if (off) (if (turn < 0) l + W * 0.02f else r - W * 0.02f) else cx + turn * pxPerDeg
            val sz = H * 0.016f
            path.reset()
            if (off) {                                   // arrow pointing off the strip
                val dir = if (turn < 0) -1f else 1f
                path.moveTo(x + dir * sz, lineY - sz * 0.9f)
                path.lineTo(x - dir * sz * 0.2f, lineY - sz * 1.8f)
                path.lineTo(x - dir * sz * 0.2f, lineY)
            } else {
                path.moveTo(x, lineY - sz * 1.3f)
                path.lineTo(x + sz * 0.75f, lineY)
                path.lineTo(x - sz * 0.75f, lineY)
            }
            path.close()
            fill.color = yellow
            c.drawPath(path, fill)
        }

        // fixed red centre line
        val bw = W * 0.016f
        val barTop = labelBase - H * 0.012f
        fill.color = red
        c.drawRect(cx - bw / 2f, barTop, cx + bw / 2f, lineY + H * 0.0025f, fill)

        // heading digits
        digits.textAlign = Paint.Align.CENTER
        digits.textSize = H * 0.052f
        digits.color = if (hdg != null) text else label
        val hs = if (hdg != null) "${f0(heading) .let { if (it == "360") "0" else it }}°" else "---°"
        c.drawText(hs, cx, digitBase, digits)
        val half = digits.measureText(hs) / 2f
        if (navTo) {
            // which way to turn, and how far: arrow over the degrees, left of the heading
            val ax = cx - half - W * 0.07f
            val sz = H * 0.013f
            path.reset()
            if (kotlin.math.abs(turn) < 2f) {
                path.moveTo(ax, digitBase - H * 0.046f); path.lineTo(ax + sz, digitBase - H * 0.030f); path.lineTo(ax - sz, digitBase - H * 0.030f)
            } else if (turn < 0) {
                path.moveTo(ax - sz, digitBase - H * 0.038f); path.lineTo(ax + sz * 0.6f, digitBase - H * 0.047f); path.lineTo(ax + sz * 0.6f, digitBase - H * 0.029f)
            } else {
                path.moveTo(ax + sz, digitBase - H * 0.038f); path.lineTo(ax - sz * 0.6f, digitBase - H * 0.047f); path.lineTo(ax - sz * 0.6f, digitBase - H * 0.029f)
            }
            path.close()
            fill.color = yellow
            c.drawPath(path, fill)
            digits.textSize = H * 0.024f
            digits.color = yellow
            c.drawText(f0(kotlin.math.abs(turn)), ax, digitBase, digits)
            // distance on the right
            labels.textAlign = Paint.Align.LEFT
            labels.textSize = H * 0.022f
            labels.color = yellow
            c.drawText(NavModel.formatDist(nav.targetDistM), cx + half + W * 0.04f, digitBase - H * 0.004f, labels)
        } else if (hdg != null) {
            labels.textAlign = Paint.Align.LEFT
            labels.textSize = H * 0.022f
            labels.color = label
            c.drawText(point16(heading), cx + half + W * 0.04f, digitBase - H * 0.004f, labels)
        } else {
            labels.textAlign = Paint.Align.LEFT
            labels.textSize = H * 0.020f
            labels.color = label
            c.drawText("no compass", cx + half + W * 0.04f, digitBase - H * 0.004f, labels)
        }
    }

    private fun openNavMenu() { navFirst = 0; dash.nav.menuOpen() }

    /** Sheet open with this waypoint selected and in view. */
    private fun openNavMenuAt(id: Int) {
        val nav = dash.nav
        nav.menuOpenAt(id)
        val row = (0 until nav.menuCount).firstOrNull { nav.menuWaypoint(it)?.id == id } ?: 0
        navFirst = (row - 1).coerceAtLeast(0)
    }

    /** The keyboard box for a waypoint's name. */
    private fun renameWaypoint(id: Int) {
        val w = dash.nav.waypoints.firstOrNull { it.id == id } ?: return
        val ask = dash.askText ?: return
        dash.nav.typing = true
        ask("Name for W${w.id}", w.label) { name ->
            dash.nav.typing = false
            if (name != null) dash.nav.renameWaypoint(w.id, name)
            invalidate()
        }
    }

    private fun drawNavMenu(c: Canvas) {
        val nav = dash.nav
        val r = drawSheet(c, "WAYPOINTS", listOf("Tap to select · hold to rename · swipe to scroll"), tall = true)
        val rowH = H * 0.062f
        val listTop = r.top + H * 0.075f
        val bh = H * 0.058f
        val by = r.bottom - H * 0.07f - bh
        val ay = by - bh - H * 0.015f                 // NAVIGATE / RENAME row for the selected waypoint
        val visible = ((ay - listTop - H * 0.01f) / rowH).toInt().coerceAtLeast(1)
        navFirst = navFirst.coerceIn(0, (nav.menuCount - visible).coerceAtLeast(0))
        var y = listTop
        for (i in navFirst until min(nav.menuCount, navFirst + visible)) {
            val lbl = nav.menuLabel(i)
            rect.set(r.left + W * 0.03f, y, r.right - W * 0.03f, y + rowH * 0.86f)
            val danger = lbl.startsWith("Tap again")
            val isNav = lbl.endsWith("◀ NAV")
            val isSel = nav.selectedId != 0 && nav.menuWaypoint(i)?.id == nav.selectedId
            fill.color = when { danger -> red; isNav -> Color.argb(120, 255, 212, 0); else -> Color.argb(60, 255, 255, 255) }
            c.drawRoundRect(rect, rowH * 0.15f, rowH * 0.15f, fill)
            if (isSel) {
                stroke.color = orange
                stroke.strokeWidth = 5f
                c.drawRoundRect(rect, rowH * 0.15f, rowH * 0.15f, stroke)
            }
            labels.textAlign = Paint.Align.LEFT
            labels.textSize = rowH * 0.40f
            labels.color = text
            c.drawText(lbl, rect.left + W * 0.03f, rect.centerY() + labels.textSize * 0.35f, labels)
            val idx = i
            val wid = nav.menuWaypoint(i)?.id
            buttons.add(Btn(RectF(rect), hold = wid?.let { { renameWaypoint(it) } }) { nav.menuSelect(idx) })
            y += rowH
        }
        val l = r.left + r.width() * 0.05f
        val rr = r.right - r.width() * 0.05f
        val gap = r.width() * 0.04f
        val third = (rr - l - gap * 2) / 3f
        val sel = nav.selected
        val half = (rr - l - gap) / 2f
        button(c, l, ay, l + half, ay + bh, if (sel != null) "NAVIGATE TO ${sel.name.uppercase()}" else "NAVIGATE",
            primary = sel != null, enabled = sel != null) { nav.navigateSelected() }
        button(c, rr - half, ay, rr, ay + bh, "RENAME", enabled = sel != null) { nav.selected?.let { renameWaypoint(it.id) } }
        button(c, l, by, l + third, by + bh, "▲", enabled = navFirst > 0) { navFirst -= visible - 1 }
        button(c, l + third + gap, by, l + 2 * third + gap, by + bh, "▼", enabled = navFirst + visible < nav.menuCount) { navFirst += visible - 1 }
        button(c, rr - third, by, rr, by + bh, "CLOSE") { nav.menuCancel() }
    }

    // ================================================================== Diagnostics
    private fun drawDiag(c: Canvas) {
        drawHeader(c, "DIAGNOSTICS")
        val s = dash.parser.stats
        val ble = dash.ble
        val g = dash.gps
        val b = dash.battery
        fun on(f: Int) = if (t.has(f)) "ON" else "off"
        val lines = listOf(
            "Swipe left or right to change page" to label,
            "" to text,
            "source  ${dash.source.label}" + (if (dash.source == Dash.Source.SIM) "  ${dash.simulator.phaseName}" else "") to text,
            "link    ${if (alive) "OK" else "NO LINK"}  ${"%.1f".format(Locale.US, s.framesPerSec)} fps" to (if (alive) green else red),
            "        ok=${s.framesOk} crc=${s.crcErrors} drops=${s.seqDrops}" to text,
            "ble     ${ble?.state ?: "-"} ${ble?.deviceName ?: ""}" to text,
            "        mtu=${ble?.mtu} rssi=${ble?.rssi} reconn=${ble?.reconnects}" to text,
            "node    sending ${when (ble?.nodeSim) { true -> "SIM data"; false -> "LIVE bike data"; null -> "-" }}" to
                (if (ble?.nodeSim != null && ble.nodeSim != (dash.source == Dash.Source.SIM)) amber else text),
            "update  ${dash.nodeUpdateStatus ?: "-"}" to when {
                dash.nodeUpdateStatus?.startsWith("ready") == true -> green
                dash.nodeUpdateStatus?.let { it.startsWith("failed") || it.startsWith("refused") } == true -> red
                dash.nodeUpdating -> amber
                else -> text
            },
            "mode    ${dash.appliance}" to text,
            "saving  ${if (dash.source == Dash.Source.LIVE) "on" else "off (SIM)"}" to text,
            "" to text,
            "rpm ${t.rpm}  gear ${if (t.gear == Telem.GEAR_UNKNOWN) "?" else t.gear}  thr ${t.throttle}" to text,
            "coolant ${t.coolantC ?: "?"}  vbat ${t.vbatV ?: "?"}" to text,
            "wheel F ${t.wheelFront}  R ${t.wheelRear}" to text,
            "CAN ${if (t.has(Telem.FLAG_CAN_OK)) "OK" else "NO DATA"}  clutch ${on(Telem.FLAG_CLUTCH_IN)}  map2 ${on(Telem.FLAG_MAP2)}" to text,
            "kill-run ${on(Telem.FLAG_KILL_RUN)}  stand-up ${on(Telem.FLAG_STAND_UP)}" to text,
            "" to text,
            "gps     ${g.source} fix=${g.fix} sats=${g.sats} ±${f0(g.accuracyM)} m" to text,
            "phone   ${if (dash.phoneGps?.enabled == true) "location on" else "location OFF"}  seen=${dash.phoneGps?.satellitesSeen ?: 0}" to text,
            "node    fix=${t.fix} sats=${t.sats} hdop=${t.hdopX10 / 10f}" to text,
            "" to text,
            "battery ${b.health}  rest ${f1(b.lastRest)} V" to text,
            "        cranks ${b.history.joinToString(" ") { f1(it) }}" to text,
            "tyres   ${dash.tpms.seen().size} sensors heard" to text,
            "trail   ${dash.nav.trail.size} pts  ${dash.nav.waypoints.size} wpts" to text,
        )
        mono.textAlign = Paint.Align.LEFT
        mono.textSize = W * 0.034f
        var y = H * 0.11f
        for ((line, col) in lines) {
            mono.color = col
            c.drawText(line, W * 0.05f, y, mono)
            y += mono.textSize * 1.42f
        }
        val by = H * 0.875f
        val bh = H * 0.055f
        val gap = W * 0.025f
        val bw = (W * 0.90f - gap * 2) / 3f
        val x0 = W * 0.05f
        // LIVE | SIM switch: the lit half is the data being shown; tap the other half to change
        val sim = dash.source == Dash.Source.SIM
        val half = (bw - W * 0.01f) / 2f
        button(c, x0, by, x0 + half, by + bh, "LIVE", selected = !sim) { dash.setSource(Dash.Source.LIVE) }
        button(c, x0 + bw - half, by, x0 + bw, by + bh, "SIM", primary = sim) { dash.setSource(Dash.Source.SIM) }
        labels.textAlign = Paint.Align.CENTER
        labels.textSize = H * 0.016f
        labels.color = label
        c.drawText("DATA SOURCE", x0 + bw / 2f, by - H * 0.010f, labels)
        // main page throttle bar on or off
        val ty = by - bh - H * 0.040f
        button(c, x0, ty, x0 + bw, ty + bh, if (dash.showThrottle) "THROTTLE ON" else "THROTTLE OFF",
            selected = dash.showThrottle) { dash.toggleThrottle() }
        labels.textAlign = Paint.Align.CENTER
        labels.textSize = H * 0.016f
        labels.color = label
        c.drawText("MAIN PAGE", x0 + bw / 2f, ty - H * 0.010f, labels)
        // CAN node over-the-air update: tap = start / stop, hold = hotspot details
        val ux = x0 + 2 * (bw + gap)
        button(c, ux, ty, ux + bw, ty + bh, if (dash.nodeUpdating) "STOP UPDATE" else "NODE UPDATE",
            primary = dash.nodeUpdating, hold = { dash.editHotspot() }) { dash.nodeUpdatePress() }
        labels.textAlign = Paint.Align.CENTER
        labels.textSize = H * 0.016f
        labels.color = label
        c.drawText("HOLD: HOTSPOT", ux + bw / 2f, ty - H * 0.010f, labels)
        button(c, x0 + bw + gap, by, x0 + 2 * bw + gap, by + bh, "BRIGHT ${dash.brightnessText}") { dash.stepBrightness() }
        button(c, x0 + 2 * (bw + gap), by, x0 + 3 * bw + 2 * gap, by + bh, if (dash.maintenanceArmed) "TAP AGAIN" else "SETTINGS",
            primary = dash.maintenanceArmed) { dash.maintenancePress() }
    }

    // ================================================================== overlays
    private fun drawHint(c: Canvas, s: String) {
        labels.textAlign = Paint.Align.CENTER
        labels.textSize = H * 0.018f
        labels.color = faint
        c.drawText(s, W / 2f, H * 0.955f, labels)
    }

    /** A dark sheet in the middle of the screen, title on top, help lines at the bottom. */
    private fun drawSheet(c: Canvas, title: String, help: List<String>, tall: Boolean = false): RectF {
        buttons.clear()                    // modal: nothing underneath takes touches
        fill.color = Color.argb(150, 0, 0, 0)
        c.drawRect(0f, 0f, W, H, fill)
        val r = RectF(W * 0.05f, H * (if (tall) 0.14f else 0.26f), W * 0.95f, H * (if (tall) 0.86f else 0.66f))
        fill.color = Color.rgb(28, 38, 43)
        c.drawRoundRect(r, W * 0.03f, W * 0.03f, fill)
        stroke.color = orange
        stroke.strokeWidth = 4f
        c.drawRoundRect(r, W * 0.03f, W * 0.03f, stroke)
        labels.textAlign = Paint.Align.CENTER
        labels.textSize = H * 0.026f
        labels.isFakeBoldText = true
        labels.color = orange
        c.drawText(title, r.centerX(), r.top + H * 0.045f, labels)
        labels.isFakeBoldText = false
        labels.textSize = H * 0.019f
        labels.color = label
        var y = r.bottom - H * 0.025f - (help.size - 1) * H * 0.028f
        for (l in help) { c.drawText(l, r.centerX(), y, labels); y += H * 0.028f }
        return r
    }

    private fun drawOption(c: Canvas, x: Float, y: Float, w: Float, h: Float, s: String, sel: Boolean) {
        rect.set(x, y, x + w, y + h)
        if (sel) { fill.color = sage; c.drawRoundRect(rect, h * 0.15f, h * 0.15f, fill) }
        else { stroke.color = faint; stroke.strokeWidth = 3f; c.drawRoundRect(rect, h * 0.15f, h * 0.15f, stroke) }
        digits.textAlign = Paint.Align.CENTER
        digits.textSize = h * 0.36f
        digits.color = if (sel) Color.BLACK else label
        c.drawText(s, rect.centerX(), rect.centerY() + digits.textSize * 0.36f, digits)
    }

    private fun drawAlert(c: Canvas, a: Dash.Alert) {
        val top = if (compact) H * 0.03f else H * 0.085f
        val h = if (compact) H * 0.44f else H * 0.11f
        rect.set(W * 0.03f, top, W * 0.97f, top + h)
        fill.color = if (a.bad) (if (flashOn()) red else Color.rgb(140, 20, 20)) else amber
        c.drawRoundRect(rect, h * 0.12f, h * 0.12f, fill)
        val fg = if (a.bad) yellow else Color.BLACK
        digits.textAlign = Paint.Align.CENTER
        digits.textSize = h * 0.38f
        digits.color = fg
        c.drawText(a.line1, W / 2f, top + h * 0.45f, digits)
        labels.textAlign = Paint.Align.CENTER
        labels.textSize = h * 0.20f
        labels.color = if (a.bad) Color.WHITE else Color.BLACK
        c.drawText(a.line2, W / 2f, top + h * 0.80f, labels)
        buttons.add(Btn(RectF(rect)) { dash.dismissAlert() })      // tap to dismiss for 5 minutes
    }

    private fun drawPageDots(c: Canvas) {
        val n = Dash.Page.entries.size
        val r = W * 0.008f
        val y = H - r * 4f
        for (i in 0 until n) {
            fill.color = if (i == dash.page.ordinal) label else faint
            c.drawCircle(W / 2f + (i - (n - 1) / 2f) * r * 4f, y, r, fill)
        }
    }

    /** Red-outlined banner: NO LINK (no frames from the node) or NO CAN DATA (node up, bike bus silent). */
    private fun drawNoLink(c: Canvas, msg: String, cy: Float, h: Float) {
        labels.textAlign = Paint.Align.CENTER
        digits.textAlign = Paint.Align.CENTER
        digits.textSize = h * 0.60f
        val w = min(W * 0.92f, max(W * 0.70f, digits.measureText(msg) + W * 0.12f))
        rect.set((W - w) / 2f, cy - h / 2f, (W + w) / 2f, cy + h / 2f)
        fill.color = Color.argb(235, 10, 14, 16)
        c.drawRoundRect(rect, h * 0.2f, h * 0.2f, fill)
        stroke.color = red
        stroke.strokeWidth = 6f
        c.drawRoundRect(rect, h * 0.2f, h * 0.2f, stroke)
        digits.color = red
        c.drawText(msg, W / 2f, cy + digits.textSize * 0.36f, digits)
    }

    private fun drawToast(c: Canvas, s: String) {
        labels.textAlign = Paint.Align.CENTER
        val u = if (compact) H * 0.30f else H * 0.10f
        labels.textSize = u * 0.24f
        labels.isFakeBoldText = true
        val tw = min(W * 0.9f, labels.measureText(s) + W * 0.10f)
        val th = u * 0.5f
        val cy = if (compact) H * 0.42f else H * 0.70f
        rect.set((W - tw) / 2f, cy - th / 2f, (W + tw) / 2f, cy + th / 2f)
        fill.color = Color.rgb(48, 60, 64)
        c.drawRoundRect(rect, th * 0.3f, th * 0.3f, fill)
        labels.color = text
        c.drawText(s, W / 2f, cy + labels.textSize * 0.36f, labels)
        labels.isFakeBoldText = false
    }

    // ================================================================== icons
    private fun drawThermometer(c: Canvas, x: Float, cy: Float, s: Float, col: Int) {
        fill.color = col
        stroke.color = col
        stroke.strokeWidth = s * 0.09f
        rect.set(x + s * 0.38f, cy - s * 0.5f, x + s * 0.62f, cy + s * 0.15f)
        c.drawRoundRect(rect, s * 0.12f, s * 0.12f, fill)
        c.drawCircle(x + s * 0.5f, cy + s * 0.28f, s * 0.22f, fill)
        for (i in 0..2) {
            val ly = cy - s * 0.4f + i * s * 0.17f
            c.drawLine(x + s * 0.68f, ly, x + s * 0.90f, ly, stroke)
        }
    }

    private fun drawBatteryIcon(c: Canvas, x: Float, y: Float, s: Float, col: Int) {
        stroke.color = col
        stroke.strokeWidth = s * 0.10f
        fill.color = col
        rect.set(x, y - s * 0.55f, x + s * 1.3f, y + s * 0.35f)
        c.drawRoundRect(rect, s * 0.08f, s * 0.08f, stroke)
        c.drawRect(x + s * 0.18f, y - s * 0.72f, x + s * 0.40f, y - s * 0.55f, fill)
        c.drawRect(x + s * 0.90f, y - s * 0.72f, x + s * 1.12f, y - s * 0.55f, fill)
        c.drawLine(x + s * 0.22f, y - s * 0.10f, x + s * 0.48f, y - s * 0.10f, stroke)
        c.drawLine(x + s * 0.82f, y - s * 0.10f, x + s * 1.08f, y - s * 0.10f, stroke)
        c.drawLine(x + s * 0.95f, y - s * 0.23f, x + s * 0.95f, y + s * 0.03f, stroke)
    }

    /** Fuel pump: body, window and nozzle hose. */
    private fun drawPump(c: Canvas, x: Float, cy: Float, s: Float, col: Int) {
        fill.color = col
        stroke.color = col
        stroke.strokeWidth = s * 0.10f
        rect.set(x, cy - s * 0.5f, x + s * 0.6f, cy + s * 0.5f)
        c.drawRoundRect(rect, s * 0.08f, s * 0.08f, fill)
        fill.color = bgTop
        c.drawRect(x + s * 0.12f, cy - s * 0.38f, x + s * 0.48f, cy - s * 0.12f, fill)
        c.drawLine(x + s * 0.6f, cy - s * 0.25f, x + s * 0.82f, cy - s * 0.1f, stroke)
        c.drawLine(x + s * 0.82f, cy - s * 0.1f, x + s * 0.82f, cy + s * 0.32f, stroke)
    }

    private fun drawArrow(c: Canvas, cx: Float, cy: Float, s: Float, up: Boolean, col: Int) {
        path.reset()
        if (up) { path.moveTo(cx, cy - s); path.lineTo(cx + s, cy + s * 0.7f); path.lineTo(cx - s, cy + s * 0.7f) }
        else { path.moveTo(cx, cy + s); path.lineTo(cx + s, cy - s * 0.7f); path.lineTo(cx - s, cy - s * 0.7f) }
        path.close()
        fill.color = col
        c.drawPath(path, fill)
    }
}
