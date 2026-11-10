// SplitScreen.kt - map app (Google Maps or Gaia GPS) on top, minimalist tacho strip on the
// bottom DashConfig.SPLIT_STRIP_FRACTION of the screen.
//
// Android's own split screen can't do this: on the Note 10 its divider only snaps to 50/50 or
// about 60/40, and an app can't close it. So the map app runs full screen and the dash lays its
// strip over the bottom of the screen as an overlay window. A foreground service keeps the
// Bluetooth link and the phone GPS running while the map app is in front.
package com.husky701.dash

import android.app.Activity
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

object SplitScreen {
    private const val TAG = "SplitScreen"
    private var strip: DashView? = null
    private var stripParams: WindowManager.LayoutParams? = null

    val showing get() = strip != null

    fun installedPackage(ctx: Context, app: Dash.MapApp): String? =
        app.packages.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    /** Every map app package, so appliance mode can let them run in lock task. */
    val allPackages: List<String> get() = Dash.MapApp.entries.flatMap { it.packages }

    /**
     * Opens the map app full screen with the tacho strip over the bottom of the screen.
     * Returns null when it worked, or a short message for the dash to show.
     */
    fun open(activity: Activity, dash: Dash, app: Dash.MapApp, brightness: Float): String? {
        val pkg = installedPackage(activity, app) ?: return "${app.label} NOT INSTALLED"
        if (!Settings.canDrawOverlays(activity)) {
            // one-off: Settings > Apps > Husky701 Dash > Appear on top (provision.ps1 does it by adb)
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")))
            }
            return "ALLOW 'APPEAR ON TOP'"
        }
        if (strip == null) showStrip(activity, dash, brightness) else setExpanded(activity, false)
        val intent = activity.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeBasic().setLaunchBounds(mapBounds(activity))
        return runCatching { activity.startActivity(intent, opts.toBundle()); null }
            .getOrElse { Log.w(TAG, "open $pkg failed", it); setExpanded(activity, true); "${app.label} WON'T OPEN" }
    }

    /**
     * Back to the full dash. The map app's pop-up window stays on top of every app (nothing an
     * app can call will close or shrink it), so the dash comes back in the overlay window instead:
     * it grows from the strip to full screen, over the map. The map app keeps running behind it.
     */
    fun close(activity: Activity) {
        if (strip != null) { setExpanded(activity, true); return }
        runCatching {
            activity.startActivity(Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.onFailure { Log.w(TAG, "return to dash failed", it) }
    }

    /** Full dash in the overlay, over whatever is covering the activity (a map pop-up). */
    fun cover(activity: Activity, dash: Dash, brightness: Float) {
        if (strip == null) showStrip(activity, dash, brightness)
        setExpanded(activity, true)
    }

    /** The overlay shows the full dash (true) or the strip (false). */
    var expanded = false
        private set

    /** The strip is up (the map is in front). */
    val stripMode get() = strip != null && !expanded

    /** True for the overlay's own view, which draws the dash; the activity behind draws nothing. */
    fun isOverlay(v: DashView) = v === strip

    /** While a text box is open the overlay lets go of the keyboard, or the typing would go to it. */
    fun yieldFocus(ctx: Context, yield: Boolean) {
        textBox = yield
        mapHasFocus = false
        setFocus(ctx, !yield)
    }

    /** One of our text boxes is open (it has the keyboard). */
    private var textBox = false
    /** The strip let go of focus so the map app can type (the rider touched the map). */
    private var mapHasFocus = false

    /**
     * The strip holds focus to keep the status and navigation bars hidden, but then the map app
     * behind can't get the keyboard. So a touch on the map hands focus to it, and a touch on the
     * strip takes it back (and hides the bars again).
     */
    private fun onStripTouch(ctx: Context, e: android.view.MotionEvent) {
        if (expanded || textBox) return
        val outside = e.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE
        if (outside && !mapHasFocus) { mapHasFocus = true; setFocus(ctx, false) }
        else if (!outside && e.actionMasked == android.view.MotionEvent.ACTION_DOWN && mapHasFocus) { mapHasFocus = false; setFocus(ctx, true) }
    }

    private fun setFocus(ctx: Context, focus: Boolean) {
        val yield = !focus
        val v = strip ?: return
        val lp = stripParams ?: return
        // ALT_FOCUSABLE_IM with NOT_FOCUSABLE puts the strip under the keyboard instead of over it
        val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        lp.flags = if (yield) lp.flags or f else lp.flags and f.inv()
        runCatching { ctx.applicationContext.getSystemService(WindowManager::class.java).updateViewLayout(v, lp) }
        if (!yield && Build.VERSION.SDK_INT >= 30) v.post {
            v.windowInsetsController?.hide(android.view.WindowInsets.Type.systemBars())
        }
    }

    private fun setExpanded(ctx: Context, full: Boolean) {
        val v = strip ?: return
        val lp = stripParams ?: return
        expanded = full
        mapHasFocus = false
        lp.height = if (full) WindowManager.LayoutParams.MATCH_PARENT
                    else (screenHeight(ctx) * DashConfig.SPLIT_STRIP_FRACTION).toInt()
        // The overlay takes focus so it can hide the status and navigation bars, in both the full
        // dash and the strip. Touching the map still works, and the bars stay hidden.
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        if (textBox) lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (Build.VERSION.SDK_INT >= 30) {
            lp.fitInsetsTypes = if (full) 0 else android.view.WindowInsets.Type.systemBars()
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        v.compact = !full
        stripDash?.compact = !full
        cap?.visibility = if (full) android.view.View.GONE else android.view.View.VISIBLE
        runCatching { ctx.applicationContext.getSystemService(WindowManager::class.java).updateViewLayout(v, lp) }
        if (Build.VERSION.SDK_INT >= 30) v.post {
            v.windowInsetsController?.let { c ->
                c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(android.view.WindowInsets.Type.systemBars())
            }
        }
    }

    private var stripDash: Dash? = null

    private fun screenHeight(ctx: Context): Int {
        val wm = ctx.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.height()
               else ctx.resources.displayMetrics.heightPixels
    }

    /**
     * The space above the strip, for the map app's window. Launching with bounds puts the map app
     * in a Samsung pop-up (freeform) window of that size. Samsung keeps a pop-up below the status
     * bar's height even when the bar is hidden (asked for 0, it moves the window down and keeps
     * its height), so the window starts there and the cap overlay covers the strip above it.
     */
    fun mapBounds(activity: Activity): Rect {
        val h = screenHeight(activity)
        val w = activity.resources.displayMetrics.widthPixels
        var top = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val ins = activity.windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            top = ins.top
        }
        // Samsung won't put a pop-up above the status bar (it moves it down, keeping the height)
        // The system bars are hidden in split, so the strip is at the very bottom of the screen.
        return Rect(0, top, w, h - (h * DashConfig.SPLIT_STRIP_FRACTION).toInt())
    }

    private fun showStrip(activity: Activity, dash: Dash, brightness: Float) {
        if (strip != null) return
        val ctx = activity.applicationContext
        val wm = ctx.getSystemService(WindowManager::class.java)
        val screenH = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.height()
                      else ctx.resources.displayMetrics.heightPixels
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (screenH * DashConfig.SPLIT_STRIP_FRACTION).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.BOTTOM
            screenBrightness = brightness
            title = "Husky701 tacho strip"
        }
        val v = DashView(ctx, dash).apply { compact = true }
        v.setOnTouchListener { _, e -> onStripTouch(ctx, e); e.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE }
        mapHasFocus = false
        textBox = false
        wm.addView(v, lp)
        strip = v
        stripParams = lp
        stripDash = dash
        expanded = false
        dash.compact = true
        addCap(activity)
        setExpanded(activity, false)
        val svc = Intent(ctx, StripService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
    }

    private var cap: android.view.View? = null

    /**
     * A thin bar over the status bar and the pop-up's handle bar. It takes the touches there, so
     * the map window can't be dragged, resized, minimised or closed from its handle bar. The strip
     * does the same along the bottom edge; the sides are the screen edges.
     */
    private fun addCap(activity: Activity) {
        if (cap != null) return
        val ctx = activity.applicationContext
        var top = 0
        if (Build.VERSION.SDK_INT >= 30) top = activity.windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()).top
        val h = top + (16 * ctx.resources.displayMetrics.density).toInt()     // status bar + handle bar
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP
            title = "Husky701 map cap"
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val v = android.view.View(ctx).apply {
            setBackgroundColor(android.graphics.Color.rgb(12, 16, 18))
            setOnTouchListener { _, _ -> true }
        }
        runCatching { ctx.getSystemService(WindowManager::class.java).addView(v, lp); cap = v }
            .onFailure { Log.w(TAG, "cap failed", it) }
    }

    fun hideStrip(ctx: Context) {
        val v = strip ?: return
        val app = ctx.applicationContext
        runCatching { app.getSystemService(WindowManager::class.java).removeView(v) }
        cap?.let { c -> runCatching { app.getSystemService(WindowManager::class.java).removeView(c) } }
        cap = null
        strip = null
        stripParams = null
        stripDash = null
        expanded = false
        app.stopService(Intent(app, StripService::class.java))
    }

    fun setBrightness(ctx: Context, level: Float) {
        val v = strip ?: return
        val lp = stripParams ?: return
        lp.screenBrightness = level
        runCatching { ctx.applicationContext.getSystemService(WindowManager::class.java).updateViewLayout(v, lp) }
    }
}

/** Keeps the dash's Bluetooth and GPS running while the map app is in front. */
class StripService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("strip", "Tacho strip", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, "strip")
        } else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Husky701 dash")
            .setContentText("Tacho strip on")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(1, n)
        return START_NOT_STICKY
    }
}
