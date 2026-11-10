// What3Words.kt - the what3words address of the current position, from the what3words API.
//
// Needs an API key (developer.what3words.com) in local.properties as w3w.apiKey=..., and a data
// connection. The key is on the free plan, so the API is only called by hand:
//  - a tap on the what3words logo (Map page) looks up the current position; nothing automatic
//  - no more than DashConfig.W3W_DAILY_CAP lookups a day, counted across restarts
//  - the last few answers are reused when back within 3 m of one of them (no lookup, and shown
//    by itself, see recall)
//  - in SIM mode the simulated position is looked up, under the same cap
package com.husky701.dash

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class What3Words(
    private val key: String,
    private val store: Store,
    private val clock: () -> Long,
    private val today: () -> Long,                     // day number, for the daily cap
    private val fetch: (String) -> String = ::httpGet,  // swapped out in tests
    private val async: Boolean = true,
) {
    /** "filled.count.soap", or null before the first answer. */
    @Volatile var words: String? = null; private set
    /** Where [words] belongs. */
    @Volatile var wordsAt: NavPoint? = null; private set
    /** Shown instead of the words when there's a problem. */
    @Volatile var status: String = if (key.isBlank()) "add w3w.apiKey" else "tap logo to look up"; private set

    val enabled get() = key.isNotBlank()

    /** Lookups made today. */
    val usedToday get() = if (store.long("w3w.day", -1L) == today()) store.int("w3w.n", 0) else 0

    /** A lookup is on its way. */
    @Volatile var busy = false; private set
    private val recent = ArrayDeque<Pair<NavPoint, String>>()

    init {
        // The last lookup survives a restart, so starting the dash again in the same place
        // doesn't spend another one.
        val lat = store.int("w3w.lat", 0)
        val lon = store.int("w3w.lon", 0)
        if (lat != 0 || lon != 0) {
            val p = NavPoint(lat, lon)
            store.string("w3w.words", "").takeIf { it.isNotEmpty() }?.let { w ->
                words = w; wordsAt = p; status = ""; recent.addFirst(p to w)
            }
        }
    }

    /** Free, call as often as you like: shows an answer we already have for this spot (same 3 m square). */
    fun recall(at: NavPoint): Boolean {
        val (p, w) = recent.firstOrNull { NavModel.distM(it.first, at) <= 3f } ?: return false
        if (wordsAt != p) { words = w; wordsAt = p; status = "" }
        return true
    }

    /** The rider tapped the logo: look up this position (one API call, unless it's already known). */
    fun lookup(at: NavPoint) {
        if (!enabled || busy) return
        if (recall(at)) return
        if (usedToday >= DashConfig.W3W_DAILY_CAP) { status = "daily limit reached"; return }

        busy = true
        store.save("w3w.day" to today(), "w3w.n" to usedToday + 1, "w3w.lat" to at.latE7, "w3w.lon" to at.lonE7, "w3w.words" to "")
        Log.i("What3Words", "lookup ${usedToday} today")
        val job = {
            try {
                val url = String.format(Locale.US,
                    "https://api.what3words.com/v3/convert-to-3wa?coordinates=%.6f,%.6f&language=en&format=json&key=%s",
                    at.latE7 / 1e7, at.lonE7 / 1e7, key)
                val json = JSONObject(fetch(url))
                if (json.has("words")) {
                    val w = json.getString("words")
                    words = w
                    wordsAt = at
                    status = ""
                    recent.addFirst(at to w)
                    store.save("w3w.words" to w)
                    while (recent.size > 20) recent.removeLast()
                } else {
                    status = when (val code = json.optJSONObject("error")?.optString("code")) {
                        "QuotaExceeded" -> "plan has no lookups left"
                        "InvalidKey", "MissingKey" -> "API key not accepted"
                        null, "" -> "error"
                        else -> code.lowercase(Locale.US)
                    }
                }
            } catch (e: Exception) {
                Log.w("What3Words", "lookup failed", e)
                status = "no connection"
            } finally {
                busy = false
            }
        }
        if (async) thread(name = "w3w") { job() } else job()
    }

    companion object {
        fun httpGet(url: String): String {
            val con = URL(url).openConnection() as HttpURLConnection
            con.connectTimeout = 5000
            con.readTimeout = 5000
            val code = con.responseCode
            return (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
        }
    }
}
