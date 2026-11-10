// Store.kt - saved state (fuel, trip, waypoints, trail, tyre sensors, battery history).
//
// Backed by SharedPreferences on the phone, or memory in unit tests. While the data
// source is SIM, nothing is written, so test rides can't touch the real records.
package com.husky701.dash

import android.content.SharedPreferences

class Store(private val prefs: SharedPreferences?) {
    var persist = true
    private val mem = HashMap<String, Any>()

    fun float(k: String, d: Float): Float = prefs?.getFloat(k, d) ?: (mem[k] as? Float ?: d)
    fun int(k: String, d: Int): Int = prefs?.getInt(k, d) ?: (mem[k] as? Int ?: d)
    fun long(k: String, d: Long): Long = prefs?.getLong(k, d) ?: (mem[k] as? Long ?: d)
    fun bool(k: String, d: Boolean): Boolean = prefs?.getBoolean(k, d) ?: (mem[k] as? Boolean ?: d)
    fun string(k: String, d: String): String = prefs?.getString(k, d) ?: (mem[k] as? String ?: d)

    fun save(vararg kv: Pair<String, Any>) {
        if (!persist) return
        if (prefs == null) {
            for ((k, v) in kv) mem[k] = v
            return
        }
        val e = prefs.edit()
        for ((k, v) in kv) when (v) {
            is Float -> e.putFloat(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Boolean -> e.putBoolean(k, v)
            is String -> e.putString(k, v)
            else -> error("unsupported type for $k")
        }
        e.apply()
    }
}
