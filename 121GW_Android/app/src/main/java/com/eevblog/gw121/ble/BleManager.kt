package com.eevblog.gw121.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.eevblog.gw121.model.PacketV2
import com.eevblog.gw121.model.SetupItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

val CHAR_121GW: UUID = UUID.fromString("E7ADD780-B042-4876-AAE1-112855353CC1")
private val SKIP_SERVICES = setOf(
    UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
)
private const val LAST_ADDR = "121gw.lastPeripheral"
private const val AUTO_RECONNECT = "121gw.autoReconnect"
private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

enum class MeterKey(val title: String, val setupArrow: String?, val longMs: Long) {
    HOLD("HOLD", null, 600),
    MODE("MODE", null, 1000),
    REL("REL", "▲", 600),
    RANGE("RANGE", null, 600),
    MIN_MAX("MIN/MAX", null, 600),
    MEM("MEM", null, 600),
    PEAK("PEAK", "▼", 600),
    SETUP("SETUP", null, 600);

    fun payload(long: Boolean): ByteArray {
        val code = when (this) {
            RANGE -> 0x31; HOLD -> 0x32; REL -> 0x33; PEAK -> 0x34
            MODE -> 0x35; MIN_MAX -> 0x36; MEM -> 0x37; SETUP -> 0x38
        }.toByte()
        val kind = (if (long) 0x38 else 0x30).toByte()
        return byteArrayOf(0xF4.toByte(), kind, code, kind, code)
    }
}

data class DiscoveredMeter(val address: String, val name: String)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("121gw", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner get() = adapter?.bluetoothLeScanner

    var isScanning = MutableStateFlow(false)
    var isConnected = MutableStateFlow(false)
    var statusText = MutableStateFlow("Ready")
    var discovered = MutableStateFlow<List<DiscoveredMeter>>(emptyList())
    var latestPacket = MutableStateFlow<PacketV2?>(null)
    var liveValue = MutableStateFlow(0.0)
    var liveUnit = MutableStateFlow("")
    var liveDisplay = MutableStateFlow("—")
    var connectedAddress = MutableStateFlow<String?>(null)
    var rssi = MutableStateFlow(0)
    var autoReconnect = MutableStateFlow(prefs.getBoolean(AUTO_RECONNECT, true))
    var packetsReceived = MutableStateFlow(0L)
    var batteryVoltage = MutableStateFlow<Double?>(null)
    var meterTime = MutableStateFlow<String?>(null)
    var communicationLost = MutableStateFlow(false)
    var inSetupMenu = MutableStateFlow(false)
    var setupItem = MutableStateFlow<SetupItem?>(null)

    val batteryLow: Boolean
        get() = batteryVoltage.value?.let { it <= 4.5 } ?: (latestPacket.value?.iconBAT == true)
    val batteryNearPowerOff: Boolean
        get() = batteryVoltage.value?.let { it <= 4.2 } ?: false

    val packetFlow = MutableSharedFlow<PacketV2>(extraBufferCapacity = 64)

    private var gatt: BluetoothGatt? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val buffer = ArrayList<Byte>(256)
    private var lastAddress: String? = prefs.getString(LAST_ADDR, null)
    private var connecting = false
    private var lastPacketAt = 0L

    private val rssiTick = object : Runnable {
        override fun run() {
            if (isConnected.value) {
                gatt?.readRemoteRssi()
                handler.postDelayed(this, 3000)
            }
        }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (isConnected.value) {
                communicationLost.value = lastPacketAt != 0L && System.currentTimeMillis() - lastPacketAt > 5000
            } else communicationLost.value = true
            handler.postDelayed(this, 1000)
        }
    }

    fun setAutoReconnect(v: Boolean) {
        autoReconnect.value = v
        prefs.edit().putBoolean(AUTO_RECONNECT, v).apply()
        if (v && !isConnected.value) watchForMeter()
    }

    fun startScan() {
        if (isConnected.value) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            statusText.value = "Bluetooth not ready"
            return
        }
        isScanning.value = true
        statusText.value = "Scanning for 121GW…"
        scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
    }

    fun stopScan() {
        scanner?.stopScan(scanCb)
        isScanning.value = false
        if (!isConnected.value) statusText.value = "Scan stopped"
    }

    fun connect(address: String) {
        if (connecting && connectedAddress.value == address) return
        connecting = true
        stopScan()
        val device = adapter?.getRemoteDevice(address) ?: return
        connectedAddress.value = address
        lastAddress = address
        prefs.edit().putString(LAST_ADDR, address).apply()
        statusText.value = "Connecting…"
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCb)
        }
    }

    fun disconnect() {
        setAutoReconnect(false)
        connecting = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected.value = false
        statusText.value = "Disconnected"
        latestPacket.value = null
        dataChar = null
        writeChar = null
        connectedAddress.value = null
        buffer.clear()
        inSetupMenu.value = false
        setupItem.value = null
        handler.removeCallbacks(rssiTick)
    }

    fun sendKey(key: MeterKey, long: Boolean = false) {
        if (!isConnected.value) {
            statusText.value = "Not connected"
            return
        }
        when (key) {
            MeterKey.SETUP -> inSetupMenu.value = true
            MeterKey.REL, MeterKey.PEAK -> {}
            else -> {
                inSetupMenu.value = false
                setupItem.value = null
            }
        }
        val ch = writeChar ?: dataChar
        if (ch == null) {
            statusText.value = "No write characteristic"
            return
        }
        val payload = key.payload(long)
        ch.value = payload
        val ok = gatt?.writeCharacteristic(ch) == true
        statusText.value = when {
            !ok -> "Write failed"
            long && key == MeterKey.MODE -> "Backlight"
            long -> "Hold ${key.title}"
            else -> "Sent ${key.title}"
        }
    }

    fun watchForMeter() {
        if (!autoReconnect.value || adapter?.isEnabled != true || isConnected.value) return
        connecting = false
        statusText.value = "Waiting for 121GW…"
        val addr = lastAddress
        if (addr != null) {
            connect(addr)
            return
        }
        startScan()
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: ""
            if (!name.contains("121GW", ignoreCase = true)) return
            val item = DiscoveredMeter(result.device.address, name.ifBlank { "121GW" })
            if (discovered.value.none { it.address == item.address }) {
                discovered.value = discovered.value + item
            }
            rssi.value = result.rssi
            if (!autoReconnect.value || isConnected.value || connecting) return
            if (lastAddress == null || result.device.address == lastAddress || discovered.value.size == 1) {
                connect(result.device.address)
            }
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connecting = false
                    isConnected.value = true
                    communicationLost.value = false
                    lastPacketAt = 0
                    handler.removeCallbacks(watchdog)
                    handler.post(watchdog)
                    dataChar = null
                    writeChar = null
                    buffer.clear()
                    statusText.value = "Connected – discovering services"
                    handler.removeCallbacks(rssiTick)
                    handler.post(rssiTick)
                    g.discoverServices()
                    BleForegroundService.start(context)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isConnected.value = false
                    communicationLost.value = true
                    connecting = false
                    dataChar = null
                    writeChar = null
                    buffer.clear()
                    handler.removeCallbacks(rssiTick)
                    BleForegroundService.stop(context)
                    if (autoReconnect.value) {
                        statusText.value = "Lost – waiting for 121GW…"
                        watchForMeter()
                    } else statusText.value = "Disconnected"
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                for (svc in g.services) {
                    if (svc.uuid in SKIP_SERVICES) continue
                    for (c in svc.characteristics) {
                        val props = c.properties
                        val canWrite = props and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        val canListen = props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                        if (c.uuid == CHAR_121GW) {
                            if (canListen) dataChar = c
                            if (canWrite) writeChar = c
                            if (canListen) enableNotify(g, c)
                            statusText.value = "Streaming data…"
                            continue
                        }
                        if (writeChar == null && canWrite) writeChar = c
                        if (dataChar == null && canListen) {
                            dataChar = c
                            enableNotify(g, c)
                            statusText.value = "Streaming data…"
                        }
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            onData(c.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onData(value)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, r: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handler.post { rssi.value = r }
        }
    }

    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        c.getDescriptor(CCCD)?.let { d ->
            d.value = BluetoothGattDescriptorCompat.enableNotificationValue()
            g.writeDescriptor(d)
        }
        statusText.value = "Live"
    }

    private fun onData(data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        handler.post { processIncoming(data) }
    }

    private fun processIncoming(data: ByteArray) {
        buffer.addAll(data.toList())
        if (buffer.size > 1024) {
            val drop = buffer.size - 256
            repeat(drop) { buffer.removeAt(0) }
        }
        var offset = 0
        var lastGood: PacketV2? = null
        val frameLen = PacketV2.LENGTH
        while (offset + frameLen <= buffer.size) {
            if ((buffer[offset].toInt() and 0xFF) != PacketV2.START) {
                offset++
                continue
            }
            val frame = ByteArray(frameLen) { buffer[offset + it] }
            val pkt = PacketV2.parse(frame)
            if (pkt != null && pkt.checksumOK) {
                lastGood = pkt
                offset += frameLen
            } else offset++
        }
        if (offset > 0) repeat(minOf(offset, buffer.size)) { buffer.removeAt(0) }
        val pkt = lastGood ?: return
        latestPacket.value = pkt
        lastPacketAt = System.currentTimeMillis()
        communicationLost.value = false
        val v = pkt.mainValue
        liveValue.value = if (v.isFinite()) v else 0.0
        liveUnit.value = pkt.mainUnit
        liveDisplay.value = pkt.displayString
        packetsReceived.value += 1
        when (pkt.subModeRaw) {
            110 -> if (pkt.subValue.isFinite()) batteryVoltage.value = kotlin.math.abs(pkt.subValue)
            140 -> {
                val n = kotlin.math.abs(pkt.subIntValue)
                val hh = minOf(23, (n shr 8) and 0xFF)
                val mm = minOf(59, n and 0xFF)
                meterTime.value = "%02d:%02d".format(hh, mm)
            }
        }
        packetFlow.tryEmit(pkt)
    }
}

private object BluetoothGattDescriptorCompat {
    fun enableNotificationValue(): ByteArray = byteArrayOf(0x01, 0x00)
}
