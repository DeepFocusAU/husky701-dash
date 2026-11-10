// Appliance.kt - turns the phone into a single-purpose dash, using Android's device-owner
// ("dedicated device") mode. No custom ROM or root needed.
//
// Once tools/provision.ps1 has made this app the device owner, every start:
//   - makes the dash the permanent home screen
//   - pins it with lock task: no status bar, notifications, home or recents buttons
//   - turns off the lock screen and keeps the screen on while powered
//   - grants its own Bluetooth permissions (no pop-ups)
//   - holds system updates to 02:00-04:00 so one can't start mid-ride
// Without device owner the dash still runs as a normal full-screen app.
package com.husky701.dash

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log

class DashAdminReceiver : DeviceAdminReceiver()

/** Gives the phone back. Only adb can send it (see the manifest). */
class ReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Appliance.release(context)
    }
}

object Appliance {
    private const val TAG = "Appliance"

    /** In-memory only: maintenance lasts until the dash restarts (reboot). */
    var maintenance = false
        private set

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(ctx: Context) = ComponentName(ctx, DashAdminReceiver::class.java)

    fun isOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    fun describe(ctx: Context): String = when {
        !isOwner(ctx) -> "normal app (not owner)"
        maintenance -> "MAINTENANCE (unlocked)"
        else -> "APPLIANCE (locked)"
    }

    /** Bluetooth (CAN node, tyre sensors) and location (phone GPS). */
    val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Applies the appliance policies. Safe to call on every start. */
    fun configure(activity: Activity) {
        if (!isOwner(activity)) return
        val dpm = dpm(activity)
        val admin = admin(activity)
        val pkg = activity.packageName
        runCatching {
            for (p in permissions) {
                dpm.setPermissionGrantState(admin, pkg, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
            // the map apps may run in lock task too, under the tacho strip
            dpm.setLockTaskPackages(admin, arrayOf(pkg) + SplitScreen.allPackages)
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            dpm.setKeyguardDisabled(admin, true)
            dpm.setStatusBarDisabled(admin, true)
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                (BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or
                    BatteryManager.BATTERY_PLUGGED_WIRELESS).toString())
            dpm.setSystemUpdatePolicy(admin, SystemUpdatePolicy.createWindowedInstallPolicy(120, 240))
            if (Build.VERSION.SDK_INT >= 30) dpm.setLocationEnabled(admin, true)   // the dash needs GPS
            // phone clock and time zone always set automatically
            dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME, "1")
            dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME_ZONE, "1")

            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, home, ComponentName(activity, MainActivity::class.java))
        }.onFailure { Log.w(TAG, "configure failed", it) }
    }

    /** Pins the dash, unless in maintenance. */
    fun lock(activity: Activity) {
        if (!isOwner(activity) || maintenance) return
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { activity.startLockTask() }.onFailure { Log.w(TAG, "startLockTask failed", it) }
        }
    }

    /** Unpins and opens Settings. The dash locks again after the next restart. */
    fun exitForMaintenance(activity: Activity) {
        maintenance = true
        if (isOwner(activity)) {
            runCatching {
                dpm(activity).setStatusBarDisabled(admin(activity), false)
                activity.stopLockTask()
            }
        }
        activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Removes every appliance policy and device-owner status. */
    fun release(ctx: Context) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        runCatching {
            dpm.setLockTaskPackages(admin, emptyArray())     // also ends lock task
            dpm.setKeyguardDisabled(admin, false)
            dpm.setStatusBarDisabled(admin, false)
            dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
            dpm.setSystemUpdatePolicy(admin, null)
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(ctx.packageName)
        }.onFailure { Log.w(TAG, "release failed", it) }
        Log.i(TAG, "device owner released")
    }
}
