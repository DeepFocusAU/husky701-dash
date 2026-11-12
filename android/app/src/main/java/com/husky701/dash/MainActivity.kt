// MainActivity.kt - the dash. Full screen, always on, portrait, and the home screen.
package com.husky701.dash

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

class MainActivity : Activity(), Dash.Platform {

    private val parser = FrameParser()
    private val simulator = Simulator()
    private lateinit var ble: BleTelemetry
    private lateinit var gps: PhoneGps
    private lateinit var compass: PhoneCompass
    private lateinit var tpmsScanner: TpmsScanner
    private lateinit var dash: Dash
    private lateinit var view: DashView
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("dash", MODE_PRIVATE) }

    // ---- Dash.Platform ----
    override var sourceName: String
        get() = prefs.getString("source", Dash.Source.LIVE.name)!!
        set(v) { prefs.edit().putString("source", v).apply() }

    override var showThrottle: Boolean
        get() = prefs.getBoolean("showThrottle", true)
        set(v) { prefs.edit().putBoolean("showThrottle", v).apply() }

    // App-private storage, on a phone that's a dedicated dash.
    override var wifiSsid: String
        get() = prefs.getString("hotspotSsid", "")!!
        set(v) { prefs.edit().putString("hotspotSsid", v).apply() }

    override var wifiPass: String
        get() = prefs.getString("hotspotPass", "")!!
        set(v) { prefs.edit().putString("hotspotPass", v).apply() }

    override var brightnessIndex: Int
        get() = prefs.getInt("brightness", DashConfig.BRIGHTNESS_DEFAULT).coerceIn(0, DashConfig.BRIGHTNESS_LEVELS.size - 1)
        set(v) { prefs.edit().putInt("brightness", v).apply() }

    override val applianceText: String get() = Appliance.describe(this)

    override var mapAppName: String
        get() = prefs.getString("mapApp", Dash.MapApp.GOOGLE.name)!!
        set(v) { prefs.edit().putString("mapApp", v).apply() }

    override fun openSplit(app: Dash.MapApp): String? =
        SplitScreen.open(this, dash, app, brightness).also { if (it == null) prefs.edit().putInt("popupBoot", bootCount).apply() }

    // Pop-ups don't survive a reboot, so "a map pop-up was opened" is remembered per boot.
    private val bootCount get() = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)

    override fun closeSplit() = SplitScreen.close(this)

    // Alarm stream: plays even with the phone on silent or vibrate.
    private val tone by lazy { runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100) }.getOrNull() }

    override fun beep() {
        val t = tone ?: return
        t.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
        main.postDelayed({ t.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150) }, 300)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        updateCompact()
    }

    /** The strip layout is used in the overlay, and also if Samsung's own split screen is used. */
    private fun updateCompact() {
        view.compact = isInMultiWindowMode
        dash.compact = isInMultiWindowMode || SplitScreen.stripMode
    }

    private var brightness = -1f

    override fun setBrightness(level: Float) {
        brightness = level
        window.attributes = window.attributes.apply { screenBrightness = level }
        SplitScreen.setBrightness(this, level)
    }

    override fun exitForMaintenance() = Appliance.exitForMaintenance(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        ble = BleTelemetry(this,
            onFrameBytes = { bytes ->
                // LIVE only, and never a frame the node marks as made-up (it switches a moment after the dash)
                val nodeSim = bytes.size == Telem.FRAME_LEN && bytes[Telem.FRAME_RESERVED_AT].toInt() and Telem.RES_NODE_SIM != 0
                if (dash.source == Dash.Source.LIVE && !nodeSim) parser.feed(bytes)
            },
            onTaps = { n -> dash.onTaps(n) })
        gps = PhoneGps(this)
        val store = Store(getSharedPreferences("dashdata", MODE_PRIVATE))
        dash = Dash(parser, simulator, ble, gps, store, this)
        tpmsScanner = TpmsScanner(this, dash.tpms)
        compass = PhoneCompass(this) { deg ->
            dash.compassDeg = deg
            if (dash.nav.fix) compass.updateDeclination(dash.nav.position.latE7, dash.nav.position.lonE7, 0f, android.os.SystemClock.elapsedRealtime())
        }
        compass.start()
        dash.w3w = What3Words(BuildConfig.W3W_API_KEY, Store(getSharedPreferences("w3w", MODE_PRIVATE)),
            { android.os.SystemClock.elapsedRealtime() }, { java.time.LocalDate.now().toEpochDay() })
        dash.askText = { title, initial, done -> askText(title, initial, done) }
        dash.askWifi = { ssid, pass, done -> askWifi(ssid, pass, done) }
        view = DashView(this, dash)
        setContentView(view)

        registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        if (!isPlugged()) main.postDelayed(screenOff, DashConfig.POWER_OFF_SCREEN_DELAY_MS)
    }

    override fun onResume() {
        super.onResume()
        // The overlay stays once the map has been opened: the map's pop-up window would cover this
        // activity. If the dash restarted with a pop-up still open, the dash goes straight to the overlay.
        if (!SplitScreen.showing && prefs.getInt("popupBoot", -1) == bootCount && hasPermissions() &&
            android.provider.Settings.canDrawOverlays(this)) SplitScreen.cover(this, dash, brightness)
        updateCompact()
        hideSystemBars()
        Appliance.configure(this)   // as device owner it grants its own permissions
        Appliance.lock(this)
        if (hasPermissions()) startRadios() else requestPermissions(Appliance.permissions, REQ_PERMS)
    }

    override fun onPause() {
        // keep everything saved in case the power goes
        dash.fuel.save(true)
        dash.nav.saveTrail()
        super.onPause()
    }

    override fun onDestroy() {
        SplitScreen.hideStrip(this)
        tone?.release()
        ble.stop()
        gps.stop()
        compass.stop()
        tpmsScanner.stop()
        unregisterReceiver(powerReceiver)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startRadios() {
        ble.start()
        gps.start()
        tpmsScanner.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* the dash has nowhere to go back to */ }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && hasPermissions()) startRadios()
    }

    private fun hasPermissions() = Appliance.permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // ---- bike power: screen follows the charger, like an ignition-switched dash ----
    private fun isPlugged(): Boolean {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private val screenOff = Runnable {
        dash.fuel.save(true)
        dash.nav.saveTrail()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Appliance.isOwner(this)) {
            (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_DISCONNECTED ->
                    main.postDelayed(screenOff, DashConfig.POWER_OFF_SCREEN_DELAY_MS)
                Intent.ACTION_POWER_CONNECTED -> {
                    main.removeCallbacks(screenOff)
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    @Suppress("DEPRECATION")
                    val wl = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "husky701:wake")
                    wl.acquire(3000)
                }
            }
        }
    }

    companion object { private const val REQ_PERMS = 1 }

    /** A text box with the keyboard up. Over the split-screen overlay when that's showing. */
    private fun askText(title: String, initial: String, done: (String?) -> Unit) {
        var answer: String? = null
        val input = android.widget.EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            isSingleLine = true
            hint = "e.g. Camp, Fuel, Gate"
            textSize = 24f
            filters = arrayOf(android.text.InputFilter.LengthFilter(DashConfig.NAV_LABEL_MAX))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("SAVE") { _, _ -> answer = input.text.toString() }
            .setNeutralButton("CLEAR") { _, _ -> answer = "" }
            .setNegativeButton("CANCEL", null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> answer = input.text.toString(); dlg.dismiss(); true }
        val overOverlay = SplitScreen.showing && android.provider.Settings.canDrawOverlays(this)
        if (overOverlay) {
            dlg.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            SplitScreen.yieldFocus(this, true)
        }
        dlg.setOnDismissListener {
            if (overOverlay) SplitScreen.yieldFocus(this, false)
            done(answer)
        }
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dlg.show()
        input.requestFocus()
    }

    /** The Wi-Fi network the CAN node joins for updates: home Wi-Fi, or the phone hotspot. */
    private fun askWifi(ssid: String, pass: String, done: (Pair<String, String>?) -> Unit) {
        var answer: Pair<String, String>? = null
        fun field(value: String, hint: String, max: Int, password: Boolean) = android.widget.EditText(this).apply {
            setText(value)
            isSingleLine = true
            this.hint = hint
            textSize = 22f
            filters = arrayOf(android.text.InputFilter.LengthFilter(max))
            if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val name = field(ssid, "Wi-Fi name", NodeOta.SSID_MAX, false)
        val word = field(pass, "Wi-Fi password", NodeOta.PASS_MAX, true)
        val show = android.widget.CheckBox(this).apply {
            text = "Show password"
            setOnCheckedChangeListener { _, on ->
                word.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    (if (on) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
                word.setSelection(word.text.length)
            }
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(name); addView(word); addView(show)
        }
        val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Wi-Fi for CAN node updates")
            .setView(box)
            .setPositiveButton("SAVE") { _, _ -> answer = name.text.toString() to word.text.toString() }
            .setNegativeButton("CANCEL", null)
            .create()
        val overOverlay = SplitScreen.showing && android.provider.Settings.canDrawOverlays(this)
        if (overOverlay) {
            dlg.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            SplitScreen.yieldFocus(this, true)
        }
        dlg.setOnDismissListener {
            if (overOverlay) SplitScreen.yieldFocus(this, false)
            done(answer)
        }
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dlg.show()
        name.requestFocus()
    }
}
