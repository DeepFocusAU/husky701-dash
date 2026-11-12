// NodeOta.kt - the CAN node's update mode (over-the-air firmware updates).
//
// NODE UPDATE on Diagnostics sends a Wi-Fi name and password (home Wi-Fi, or the phone hotspot
// when away from home) to the node over BLE (DashConfig.BLE_OTA). The node joins that network and
// waits up to 10 minutes for an upload from VS Code: `pio run -e node_ota -t upload`, with the
// computer on the same network.
// See can-node/src/wifi_ota.h for the node side and the status texts it sends back.
package com.husky701.dash

object NodeOta {
    const val SSID_MAX = 32     // bytes, Wi-Fi limit
    const val PASS_MAX = 63     // bytes, WPA2 passphrase limit

    /** Why these Wi-Fi details can't be used, or null if they're fine. */
    fun problem(ssid: String, pass: String): String? {
        val s = ssid.toByteArray(Charsets.UTF_8).size
        val p = pass.toByteArray(Charsets.UTF_8).size
        return when {
            s == 0 -> "NO WI-FI NAME"
            s > SSID_MAX -> "WI-FI NAME TOO LONG"
            p in 1..7 -> "PASSWORD TOO SHORT"
            p > PASS_MAX -> "PASSWORD TOO LONG"
            else -> null
        }
    }

    /** [1][ssid len][ssid][pass len][pass], or null if the details aren't usable. */
    fun startPayload(ssid: String, pass: String): ByteArray? {
        if (problem(ssid, pass) != null) return null
        val s = ssid.toByteArray(Charsets.UTF_8)
        val p = pass.toByteArray(Charsets.UTF_8)
        return byteArrayOf(1, s.size.toByte()) + s + byteArrayOf(p.size.toByte()) + p
    }

    val stopPayload = byteArrayOf(0)

    /** The status the node sent, or null for its "received" echo. Never shows raw bytes (older node
     *  firmware could leave the written Wi-Fi details readable). */
    fun statusText(value: ByteArray): String? {
        if (value.any { (it.toInt() and 0xFF) < 0x20 || (it.toInt() and 0xFF) > 0x7E }) return "unreadable (update the node by USB)"
        val s = String(value, Charsets.US_ASCII)
        return if (s == "received") null else s
    }

    /** The node is joining Wi-Fi, waiting for an upload, or uploading. */
    fun active(status: String?): Boolean =
        status != null && (status.startsWith("connecting") || status.startsWith("ready") || status.startsWith("updating"))
}
