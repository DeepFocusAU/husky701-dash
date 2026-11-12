// BleTelemetry.kt - connects to the CAN node over Bluetooth LE and receives frames and taps.
//
// Scans for the node's service UUID, connects, asks for MTU 247 (a frame is 42 bytes) and a
// fast connection interval, then subscribes to the Frame and Taps characteristics. If the
// link drops it scans again by itself, so key-off / key-on needs nothing from the rider.
// It also writes the Mode characteristic (LIVE or SIM) on every connect and whenever the
// dash's data source changes; the node goes back to LIVE by itself when the link drops.
// The Ota characteristic starts the node's Wi-Fi update mode (see NodeOta.kt); writing it needs
// an encrypted link, so the first time Android pairs with the node ("just works", no PIN).
// The UUIDs must match BLE_* in the CAN node's include/config.h.
package com.husky701.dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log

@SuppressLint("MissingPermission")   // MainActivity only starts this once permissions are granted
class BleTelemetry(
    private val context: Context,
    /** Called on the main thread with each notification from the Frame characteristic. */
    private val onFrameBytes: (ByteArray) -> Unit,
    /** Called on the main thread with the tap count of each finished gesture. */
    private val onTaps: (Int) -> Unit,
) {
    enum class State { OFF, NO_BLUETOOTH, SCANNING, CONNECTING, CONNECTED }

    var state = State.OFF
        private set
    var deviceName: String = ""
        private set
    var mtu = 23
        private set
    var rssi = 0
        private set
    var reconnects = 0
        private set

    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var scanning = false
    private var lastTapSeq = -1

    /** What the dash wants the node to send: true = its made-up test data. */
    private var wantNodeSim = false
    /** What the node says it's sending, from its frames (null until one arrives). */
    var nodeSim: Boolean? = null
        private set

    /** The node's update-mode status (see NodeOta), or null if its firmware doesn't have one. */
    var otaStatus: String? = null
        private set
    /** Waiting to be written to the node (kept only until it's accepted). */
    private var otaPayload: ByteArray? = null
    private var otaAttempts = 0

    /** Starts the node's update mode on this hotspot. */
    fun startNodeOta(ssid: String, pass: String) {
        otaPayload = NodeOta.startPayload(ssid, pass) ?: return
        otaAttempts = 0
        otaStatus = "sending hotspot details"
        gatt?.let { writeOta(it) }
    }

    fun stopNodeOta() {
        otaPayload = NodeOta.stopPayload
        otaAttempts = 0
        gatt?.let { writeOta(it) }
    }

    private fun writeOta(g: BluetoothGatt) {
        val v = otaPayload ?: return
        val c = g.getService(DashConfig.BLE_SERVICE)?.getCharacteristic(DashConfig.BLE_OTA) ?: return
        if (state != State.CONNECTED) return
        val ok = write(g, c, v)
        if (!ok) retryOta(g)
    }

    /** Busy, or the write needs pairing first (Android pairs by itself; "just works", no PIN). */
    private fun retryOta(g: BluetoothGatt) {
        if (++otaAttempts > 15) {
            otaPayload = null
            otaStatus = "failed: node didn't accept (pairing?)"
            return
        }
        if (otaAttempts == 3 && g.device.bondState == BluetoothDevice.BOND_NONE) {
            otaStatus = "pairing with node"
            g.device.createBond()
        }
        main.postDelayed({ if (gatt === g) writeOta(g) }, 1000)
    }

    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = v
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }

    /** Dash data source changed (or start-up): tell the node, now if connected, else on connect. */
    fun setNodeSim(sim: Boolean) {
        wantNodeSim = sim
        if (state == State.CONNECTED) gatt?.let { writeMode(it) }
    }

    private fun writeMode(g: BluetoothGatt, attempt: Int = 0) {
        val c = g.getService(DashConfig.BLE_SERVICE)?.getCharacteristic(DashConfig.BLE_MODE) ?: return  // older node firmware
        val ok = write(g, c, byteArrayOf(if (wantNodeSim) 1 else 0))
        // busy with another request: try again shortly
        if (!ok && attempt < 10) main.postDelayed({ if (gatt === g && state == State.CONNECTED) writeMode(g, attempt + 1) }, 200)
        Log.i("BleTelemetry", "mode -> ${if (wantNodeSim) "SIM" else "LIVE"} ok=$ok")
    }

    /** Subscriptions done: the link is up. */
    private fun ready(g: BluetoothGatt) {
        state = State.CONNECTED
        writeMode(g)
        val ota = g.getService(DashConfig.BLE_SERVICE)?.getCharacteristic(DashConfig.BLE_OTA)
        if (ota == null) otaStatus = null
        else {
            if (otaStatus == null) otaStatus = "off"
            // read what the node is doing now, a moment after the mode write
            main.postDelayed({ if (gatt === g) g.readCharacteristic(ota) }, 400)
        }
    }

    fun start() {
        if (running) return
        running = true
        startScan()
    }

    fun stop() {
        running = false
        stopScan()
        gatt?.close()
        gatt = null
        state = State.OFF
    }

    private fun startScan() {
        if (!running) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            state = State.NO_BLUETOOTH
            main.postDelayed({ startScan() }, 2000)
            return
        }
        val scanner = a.bluetoothLeScanner ?: run { main.postDelayed({ startScan() }, 2000); return }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(DashConfig.BLE_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        state = State.SCANNING
        scanning = true
        scanner.startScan(listOf(filter), settings, scanCb)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (state != State.SCANNING) return
            stopScan()
            rssi = result.rssi
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
            scanning = false
            main.postDelayed({ startScan() }, 2000)
        }
    }

    private fun connect(device: BluetoothDevice) {
        state = State.CONNECTING
        deviceName = device.name ?: device.address
        gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun retryLater() {
        otaPayload = null
        if (NodeOta.active(otaStatus) || otaStatus == "sending hotspot details" || otaStatus == "pairing with node")
            otaStatus = "unknown (link lost)"
        gatt?.close()
        gatt = null
        if (!running) return
        reconnects++
        state = State.SCANNING
        main.postDelayed({ startScan() }, 1000)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.requestMtu(247)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    retryLater()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            main.post { mtu = newMtu; g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val svc = g.getService(DashConfig.BLE_SERVICE)
                if (svc == null) { g.disconnect(); return@post }
                // Descriptor writes must go one at a time: Frame now, Taps in onDescriptorWrite.
                subscribe(g, svc.getCharacteristic(DashConfig.BLE_FRAME))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (d.characteristic.uuid == DashConfig.BLE_FRAME) {
                    val taps = g.getService(DashConfig.BLE_SERVICE)?.getCharacteristic(DashConfig.BLE_TAPS)
                    if (taps != null) subscribe(g, taps) else ready(g)
                } else if (d.characteristic.uuid == DashConfig.BLE_TAPS) {
                    val ota = g.getService(DashConfig.BLE_SERVICE)?.getCharacteristic(DashConfig.BLE_OTA)
                    if (ota != null) subscribe(g, ota) else ready(g)
                } else {
                    ready(g)
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (c.uuid != DashConfig.BLE_OTA) return
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    otaPayload = null           // don't keep the password around
                    if (otaStatus == "sending hotspot details" || otaStatus == "pairing with node") otaStatus = "sent"
                } else {
                    Log.i(TAG, "ota write status $status")
                    retryOta(g)
                }
            }
        }

        // Android 13+
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handle(c.uuid, value.copyOf())
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (status == BluetoothGatt.GATT_SUCCESS) handle(c.uuid, c.value?.copyOf() ?: return)
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handle(c.uuid, value.copyOf())
        }

        // Android 12 and older (the Note 10)
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handle(c.uuid, c.value?.copyOf() ?: return)
        }
    }

    private fun handle(uuid: java.util.UUID, value: ByteArray) {
        main.post {
            when (uuid) {
                DashConfig.BLE_FRAME -> {
                    if (value.size == Telem.FRAME_LEN) nodeSim = value[Telem.FRAME_RESERVED_AT].toInt() and Telem.RES_NODE_SIM != 0
                    onFrameBytes(value)
                }
                DashConfig.BLE_TAPS -> if (value.size >= 2) {
                    val seq = value[0].toInt() and 0xFF
                    val taps = value[1].toInt() and 0xFF
                    // The node only notifies when a gesture finishes; the seq guards against
                    // the same notification arriving twice.
                    if (seq != lastTapSeq) {
                        lastTapSeq = seq
                        if (taps in 1..DashConfig.TAP_MAX) onTaps(taps)
                    }
                }
                // "received" is only the node clearing the written password: not a status
                DashConfig.BLE_OTA -> String(value, Charsets.UTF_8).let { if (it != "received") otaStatus = it }
            }
        }
    }

    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic?) {
        if (c == null) { g.disconnect(); return }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(DashConfig.CCCD) ?: run { ready(g); return }
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    companion object { private const val TAG = "BleTelemetry" }
}
