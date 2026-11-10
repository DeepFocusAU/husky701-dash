// Tpms.kt - Bluetooth valve-cap tyre pressure sensors, passive scan. Port of tpms.cpp.
//
// Decoders follow bkbilly/tpms_ble:
//   FOBO Bike 2 / Salutica   company id 295 (0x0127), or service data 0x012A  <- recommended
//   TPMSII ("Type A")        company id 256, 16-byte payload
//   WODHMIEY / ITPMS ("D")   company id 172
//   SYTPMS ("Type B")        service UUID 0x27A5
// Pressures are gauge, in bar. Nothing is paired at the Bluetooth level, so the sensors
// keep working with their own app.
package com.husky701.dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TpmsDecoded(val type: String, val bar: Float, val tempC: Int, val battV: Float)

object TpmsDecoders {
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** FOBO service-data variant: last 4 bytes, temperature offset 40. */
    fun foboServiceData(sd: ByteArray): TpmsDecoded? {
        if (sd.size < 4) return null
        val o = sd.size - 4
        val raw = (u8(sd, o + 2) shl 8) or u8(sd, o + 3)
        return TpmsDecoded("FOBO", (raw and 1023) * 0.01f, (u8(sd, o + 1) and 0x7F) - 40, ((raw shr 10) and 0x1F) * 0.1f)
    }

    /** Manufacturer data: company id plus the bytes after it. */
    fun manufacturer(company: Int, p: ByteArray, advertisesSytpms: Boolean): TpmsDecoded? {
        val n = p.size
        if (advertisesSytpms && n == 5) {
            val psi = (((u8(p, 1) shl 8) or u8(p, 2)) - 145) / 10f
            return TpmsDecoded("SYTPMS", psi * 0.0689476f, p[0].toInt(), ((company shr 8) and 0xFF) / 10f)
        }
        return when {
            company == 295 && n >= 4 -> {
                val o = n - 4
                val raw = (u8(p, o + 2) shl 8) or u8(p, o + 3)
                TpmsDecoded("FOBO", (raw and 1023) * 0.01f, (u8(p, o + 1) and 0x7F) - 50, ((raw shr 10) and 0x1F) * 0.1f)
            }
            company == 256 && n == 16 -> {
                val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
                TpmsDecoded("TPMSII", b.getInt(6) / 100000f, b.getInt(10) / 100, 0f)
            }
            company == 172 && n >= 7 -> {
                val raw = u8(p, 1) + if (u8(p, 6) == 1) 256 else 0
                TpmsDecoded("ITPMS", raw * 0.03144f, u8(p, 2) - 55, u8(p, 0) * 0.01f + 1.22f)
            }
            else -> null
        }
    }
}

class TyreReading(
    val paired: Boolean, val tag: String,
    val valid: Boolean = false, val bar: Float = 0f, val tempC: Int = 0, val battV: Float = 0f,
    val ageS: Long = 0, val rssi: Int = 0,
) {
    val stale get() = ageS > DashConfig.TPMS_STALE_S
}

class SeenSensor(val mac: String, val tag: String, val type: String, val bar: Float, val rssi: Int, val ageS: Long)

class TpmsModel(private val store: Store, private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private class Slot(var type: String, var bar: Float, var tempC: Int, var battV: Float, var rssi: Int, var lastMs: Long)

    private val slots = LinkedHashMap<String, Slot>()     // by MAC "AA:BB:CC:DD:EE:FF"
    var frontMac = ""; private set
    var rearMac = ""; private set

    var pairing = false; private set
    var pairStep = 0; private set                          // 0 = choosing FRONT, 1 = REAR
    var pairSel = 0; private set
    private var pairMs = 0L

    init { load() }

    fun load() {
        frontMac = store.string("tpms.front", "")
        rearMac = store.string("tpms.rear", "")
    }

    fun onSensor(mac: String, d: TpmsDecoded, rssi: Int) {
        if (d.bar < 0 || d.bar > 6f) return
        val s = slots.getOrPut(mac) { Slot(d.type, 0f, 0, 0f, rssi, 0) }
        s.type = d.type; s.bar = d.bar; s.tempC = d.tempC.coerceIn(-40, 125); s.battV = d.battV
        s.rssi = rssi; s.lastMs = clock()
        if (slots.size > 16) slots.remove(slots.minByOrNull { it.value.lastMs }!!.key)
    }

    fun update() {
        if (pairing && clock() - pairMs > DashConfig.TPMS_PAIR_TIMEOUT_MS) pairing = false
    }

    private fun tag(mac: String) = if (mac.length >= 5) mac.takeLast(5) else mac

    private fun reading(mac: String): TyreReading {
        if (mac.isEmpty()) return TyreReading(false, "")
        val s = slots[mac] ?: return TyreReading(true, tag(mac))
        return TyreReading(true, tag(mac), true, s.bar, s.tempC, s.battV, (clock() - s.lastMs) / 1000, s.rssi)
    }

    val front get() = reading(frontMac)
    val rear get() = reading(rearMac)

    /** Sensors heard in the last 2 minutes, strongest first. */
    fun seen(): List<SeenSensor> {
        val now = clock()
        return slots.filter { now - it.value.lastMs < 120_000 }
            .map { (mac, s) -> SeenSensor(mac, tag(mac), s.type, s.bar, s.rssi, (now - s.lastMs) / 1000) }
            .sortedByDescending { it.rssi }
            .take(5)
    }

    // ---- pairing ----
    fun pairOpen() { pairing = true; pairStep = 0; pairSel = 0; pairMs = clock() }
    fun pairCancel() { pairing = false }
    fun pairSelect(i: Int) { pairSel = i; pairMs = clock() }
    fun pairNext() { val n = seen().size; pairSel = if (n > 0) (pairSel + 1) % n else 0; pairMs = clock() }
    fun pairSkip() {
        pairMs = clock()
        if (pairStep == 0) { pairStep = 1; pairSel = 0 } else pairing = false
    }
    fun pairAssign() {
        pairMs = clock()
        val list = seen()
        if (pairSel >= list.size) return
        val mac = list[pairSel].mac
        if (pairStep == 0) { frontMac = mac; pairStep = 1; pairSel = 0 }
        else { rearMac = mac; pairing = false }
        store.save("tpms.front" to frontMac, "tpms.rear" to rearMac)
    }
}

/** Listens to every BLE advert and hands valve-cap readings to the model. */
@SuppressLint("MissingPermission")
class TpmsScanner(private val context: Context, private val model: TpmsModel) {
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private val foboSvc = ParcelUuid.fromString("0000012a-0000-1000-8000-00805f9b34fb")
    private val sytpmsSvc = ParcelUuid.fromString("000027a5-0000-1000-8000-00805f9b34fb")

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) = handle(r)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
        override fun onScanFailed(errorCode: Int) {
            running = false
            main.postDelayed({ start() }, 5000)
        }
    }

    private fun handle(r: ScanResult) {
        val rec = r.scanRecord ?: return
        val mac = r.device.address
        rec.getServiceData(foboSvc)?.let { sd ->
            TpmsDecoders.foboServiceData(sd)?.let { d -> main.post { model.onSensor(mac, d, r.rssi) } }
            return
        }
        val md = rec.manufacturerSpecificData ?: return
        if (md.size() == 0) return
        val sytpms = rec.serviceUuids?.contains(sytpmsSvc) == true
        val company = md.keyAt(0)
        val d = TpmsDecoders.manufacturer(company, md.valueAt(0), sytpms) ?: return
        main.post { model.onSensor(mac, d, r.rssi) }
    }

    fun start() {
        if (running || !DashConfig.TPMS_ENABLE) return
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bm.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) { main.postDelayed({ start() }, 5000); return }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        scanner.startScan(null, settings, cb)
        running = true
    }

    fun stop() {
        if (!running) return
        running = false
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        runCatching { bm.adapter?.bluetoothLeScanner?.stopScan(cb) }
    }
}
