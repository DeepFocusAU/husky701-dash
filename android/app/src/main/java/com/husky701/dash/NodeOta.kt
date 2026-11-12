// NodeOta.kt - the CAN node's update mode (over-the-air firmware updates).
//
// NODE UPDATE on Diagnostics sends the phone's hotspot name and password to the node over BLE
// (DashConfig.BLE_OTA). The node joins the hotspot and waits up to 10 minutes for an upload
// from VS Code: `pio run -e node_ota -t upload`, with the computer on the same hotspot.
// See can-node/src/wifi_ota.h for the node side and the status texts it sends back.
package com.husky701.dash

object NodeOta {
    const val SSID_MAX = 32     // bytes, Wi-Fi limit
    const val PASS_MAX = 63     // bytes, WPA2 passphrase limit

    /** Why these hotspot details can't be used, or null if they're fine. */
    fun problem(ssid: String, pass: String): String? {
        val s = ssid.toByteArray(Charsets.UTF_8).size
        val p = pass.toByteArray(Charsets.UTF_8).size
        return when {
            s == 0 -> "NO HOTSPOT NAME"
            s > SSID_MAX -> "HOTSPOT NAME TOO LONG"
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

    /** The node is joining the hotspot, waiting for an upload, or uploading. */
    fun active(status: String?): Boolean =
        status != null && (status.startsWith("connecting") || status.startsWith("ready") || status.startsWith("updating"))
}
