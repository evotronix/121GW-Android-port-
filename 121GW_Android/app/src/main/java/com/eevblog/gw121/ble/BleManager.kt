package com.eevblog.gw121.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import com.eevblog.gw121.model.PacketV2
import com.eevblog.gw121.model.SetupItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

val CHAR_121GW: UUID =
    UUID.fromString("E7ADD780-B042-4876-AAE1-112855353CC1")

private val SKIP_SERVICES = setOf(
    UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
)

private const val LAST_ADDR = "121gw.lastPeripheral"
private const val AUTO_RECONNECT = "121gw.autoReconnect"

private val CCCD =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

enum class MeterKey(
    val title: String,
    val setupArrow: String?,
    val longMs: Long
) {
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
            RANGE -> 0x31
            HOLD -> 0x32
            REL -> 0x33
            PEAK -> 0x34
            MODE -> 0x35
            MIN_MAX -> 0x36
            MEM -> 0x37
            SETUP -> 0x38
        }.toByte()

        val kind = (if (long) 0x38 else 0x30).toByte()

        return byteArrayOf(
            0xF4.toByte(),
            kind,
            code,
            kind,
            code
        )
    }
}

data class DiscoveredMeter(
    val address: String,
    val name: String
)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "121GW-BLE"
    }

    private val prefs =
        context.getSharedPreferences("121gw", Context.MODE_PRIVATE)

    private val handler =
        Handler(Looper.getMainLooper())

    private val adapter: BluetoothAdapter? =
        (
            context.getSystemService(
                Context.BLUETOOTH_SERVICE
            ) as BluetoothManager
        ).adapter

    private val scanner
        get() = adapter?.bluetoothLeScanner

    var isScanning =
        MutableStateFlow(false)

    var isConnected =
        MutableStateFlow(false)

    var statusText =
        MutableStateFlow("Ready")

    var discovered =
        MutableStateFlow<List<DiscoveredMeter>>(emptyList())

    var latestPacket =
        MutableStateFlow<PacketV2?>(null)

    var liveValue =
        MutableStateFlow(0.0)

    var liveUnit =
        MutableStateFlow("")

    var liveDisplay =
        MutableStateFlow("—")

    var connectedAddress =
        MutableStateFlow<String?>(null)

    var rssi =
        MutableStateFlow(0)

    var autoReconnect =
        MutableStateFlow(
            prefs.getBoolean(AUTO_RECONNECT, true)
        )

    /**
     * Number of valid PacketV2 packets received.
     */
    var packetsReceived =
        MutableStateFlow(0L)

    /**
     * Number of raw BLE payload bytes received.
     *
     * This is deliberately separate from packetsReceived so we can
     * distinguish:
     *
     *   no BLE data
     *       from
     *   BLE data received but invalid packet
     */
    var bytesReceived =
        MutableStateFlow(0L)

    var batteryVoltage =
        MutableStateFlow<Double?>(null)

    var meterTime =
        MutableStateFlow<String?>(null)

    var communicationLost =
        MutableStateFlow(false)

    var inSetupMenu =
        MutableStateFlow(false)

    var setupItem =
        MutableStateFlow<SetupItem?>(null)

    val batteryLow: Boolean
        get() =
            batteryVoltage.value?.let {
                it <= 4.5
            } ?: (latestPacket.value?.iconBAT == true)

    val batteryNearPowerOff: Boolean
        get() =
            batteryVoltage.value?.let {
                it <= 4.2
            } ?: false

    val packetFlow =
        MutableSharedFlow<PacketV2>(
            extraBufferCapacity = 64
        )

    private var gatt: BluetoothGatt? = null

    private var dataChar: BluetoothGattCharacteristic? = null

    private var writeChar: BluetoothGattCharacteristic? = null

    /**
     * True only after Android confirms the CCCD write succeeded.
     */
    private var notificationsEnabled = false

    private val buffer =
        ArrayList<Byte>(256)

    private var lastAddress: String? =
        prefs.getString(LAST_ADDR, null)

    private var connecting = false

    private var lastPacketAt = 0L

    private val rssiTick = object : Runnable {
        override fun run() {
            if (isConnected.value) {
                try {
                    gatt?.readRemoteRssi()
                } catch (e: Exception) {
                    Log.e(TAG, "readRemoteRssi failed", e)
                }

                handler.postDelayed(
                    this,
                    3000
                )
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (isConnected.value) {
                communicationLost.value =
                    lastPacketAt != 0L &&
                        System.currentTimeMillis() - lastPacketAt > 5000
            } else {
                communicationLost.value = true
            }

            handler.postDelayed(
                this,
                1000
            )
        }
    }

    fun setAutoReconnect(v: Boolean) {
        autoReconnect.value = v

        prefs.edit()
            .putBoolean(AUTO_RECONNECT, v)
            .apply()

        if (v && !isConnected.value) {
            watchForMeter()
        }
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

        scanner?.startScan(
            null,
            ScanSettings.Builder()
                .setScanMode(
                    ScanSettings.SCAN_MODE_LOW_LATENCY
                )
                .build(),
            scanCb
        )
    }

    fun stopScan() {
        scanner?.stopScan(scanCb)

        isScanning.value = false

        if (!isConnected.value) {
            statusText.value = "Scan stopped"
        }
    }

    fun connect(address: String) {
        if (
            connecting &&
            connectedAddress.value == address
        ) {
            return
        }

        connecting = true

        stopScan()

        val device =
            try {
                adapter?.getRemoteDevice(address)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to get remote device $address", e)
                null
            }

        if (device == null) {
            connecting = false
            statusText.value = "Invalid Bluetooth device"
            return
        }

        connectedAddress.value = address

        lastAddress = address

        prefs.edit()
            .putString(LAST_ADDR, address)
            .apply()

        statusText.value = "Connecting…"

        Log.d(
            TAG,
            "Connecting to $address"
        )

        gatt =
            if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(
                    context,
                    false,
                    gattCb,
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                device.connectGatt(
                    context,
                    false,
                    gattCb
                )
            }
    }

    fun disconnect() {
        setAutoReconnect(false)

        connecting = false

        notificationsEnabled = false

        try {
            gatt?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "disconnect failed", e)
        }

        try {
            gatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "close failed", e)
        }

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

    fun sendKey(
        key: MeterKey,
        long: Boolean = false
    ) {
        if (!isConnected.value) {
            statusText.value = "Not connected"
            return
        }

        when (key) {
            MeterKey.SETUP -> {
                inSetupMenu.value = true
            }

            MeterKey.REL,
            MeterKey.PEAK -> {
                // No state change.
            }

            else -> {
                inSetupMenu.value = false
                setupItem.value = null
            }
        }

        val ch =
            writeChar ?: dataChar

        if (ch == null) {
            statusText.value = "No write characteristic"
            return
        }

        val payload =
            key.payload(long)

        ch.value = payload

        val ok =
            try {
                gatt?.writeCharacteristic(ch) == true
            } catch (e: Exception) {
                Log.e(TAG, "writeCharacteristic failed", e)
                false
            }

        Log.d(
            TAG,
            "TX ${payload.toHexString()} " +
                "key=${key.title} long=$long ok=$ok"
        )

        statusText.value =
            when {
                !ok -> "Write failed"

                long && key == MeterKey.MODE ->
                    "Backlight"

                long ->
                    "Hold ${key.title}"

                else ->
                    "Sent ${key.title}"
            }
    }

    fun watchForMeter() {
        if (
            !autoReconnect.value ||
            adapter?.isEnabled != true ||
            isConnected.value
        ) {
            return
        }

        connecting = false

        statusText.value =
            "Waiting for 121GW…"

        val addr = lastAddress

        if (addr != null) {
            connect(addr)
            return
        }

        startScan()
    }

    private val scanCb =
        object : ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {
                val name =
                    result.device.name
                        ?: result.scanRecord?.deviceName
                        ?: ""

                if (
                    !name.contains(
                        "121GW",
                        ignoreCase = true
                    )
                ) {
                    return
                }

                val item =
                    DiscoveredMeter(
                        result.device.address,
                        name.ifBlank { "121GW" }
                    )

                if (
                    discovered.value.none {
                        it.address == item.address
                    }
                ) {
                    discovered.value =
                        discovered.value + item
                }

                rssi.value =
                    result.rssi

                if (
                    !autoReconnect.value ||
                    isConnected.value ||
                    connecting
                ) {
                    return
                }

                if (
                    lastAddress == null ||
                    result.device.address == lastAddress ||
                    discovered.value.size == 1
                ) {
                    connect(
                        result.device.address
                    )
                }
            }

            override fun onScanFailed(
                errorCode: Int
            ) {
                Log.e(
                    TAG,
                    "BLE scan failed: $errorCode"
                )

                handler.post {
                    isScanning.value = false
                    statusText.value =
                        "BLE scan failed: $errorCode"
                }
            }
        }

    private val gattCb =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                Log.d(
                    TAG,
                    "onConnectionStateChange " +
                        "status=$status " +
                        "newState=$newState"
                )

                handler.post {
                    if (
                        newState ==
                        BluetoothProfile.STATE_CONNECTED
                    ) {
                        connecting = false

                        isConnected.value = true

                        communicationLost.value = false

                        lastPacketAt = 0

                        notificationsEnabled = false

                        handler.removeCallbacks(
                            watchdog
                        )

                        handler.post(watchdog)

                        dataChar = null
                        writeChar = null

                        buffer.clear()

                        statusText.value =
                            "Connected – discovering services"

                        handler.removeCallbacks(
                            rssiTick
                        )

                        handler.post(rssiTick)

                        val ok =
                            try {
                                g.discoverServices()
                            } catch (e: Exception) {
                                Log.e(
                                    TAG,
                                    "discoverServices failed",
                                    e
                                )
                                false
                            }

                        Log.d(
                            TAG,
                            "discoverServices requested=$ok"
                        )

                        if (!ok) {
                            statusText.value =
                                "Service discovery request failed"
                        }

                        BleForegroundService.start(
                            context
                        )
                    } else if (
                        newState ==
                        BluetoothProfile.STATE_DISCONNECTED
                    ) {
                        Log.d(
                            TAG,
                            "Disconnected status=$status"
                        )

                        isConnected.value = false

                        communicationLost.value = true

                        connecting = false

                        notificationsEnabled = false

                        dataChar = null
                        writeChar = null

                        buffer.clear()

                        handler.removeCallbacks(
                            rssiTick
                        )

                        BleForegroundService.stop(
                            context
                        )

                        if (autoReconnect.value) {
                            statusText.value =
                                "Lost – waiting for 121GW…"

                            watchForMeter()
                        } else {
                            statusText.value =
                                "Disconnected"
                        }
                    }
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int
            ) {
                Log.d(
                    TAG,
                    "onServicesDiscovered " +
                        "status=$status " +
                        "services=${g.services.size}"
                )

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {
                    handler.post {
                        statusText.value =
                            "Service discovery failed: $status"
                    }

                    return
                }

                handler.post {
                    var foundTarget = false

                    for (svc in g.services) {

                        if (
                            svc.uuid in SKIP_SERVICES
                        ) {
                            continue
                        }

                        Log.d(
                            TAG,
                            "Service ${svc.uuid} " +
                                "characteristics=" +
                                "${svc.characteristics.size}"
                        )

                        for (c in svc.characteristics) {

                            val props =
                                c.properties

                            val canWrite =
                                props and (
                                    BluetoothGattCharacteristic
                                        .PROPERTY_WRITE or
                                        BluetoothGattCharacteristic
                                        .PROPERTY_WRITE_NO_RESPONSE
                                ) != 0

                            val canListen =
                                props and (
                                    BluetoothGattCharacteristic
                                        .PROPERTY_NOTIFY or
                                        BluetoothGattCharacteristic
                                        .PROPERTY_INDICATE
                                ) != 0

                            Log.d(
                                TAG,
                                "Characteristic ${c.uuid} " +
                                    "props=0x${props.toString(16)} " +
                                    "write=$canWrite " +
                                    "listen=$canListen"
                            )

                            if (
                                c.uuid ==
                                CHAR_121GW
                            ) {
                                foundTarget = true

                                Log.d(
                                    TAG,
                                    "FOUND 121GW characteristic " +
                                        "${c.uuid}"
                                )

                                if (canListen) {
                                    dataChar = c

                                    enableNotify(
                                        g,
                                        c
                                    )
                                } else {
                                    Log.e(
                                        TAG,
                                        "121GW characteristic does " +
                                            "not support NOTIFY/INDICATE"
                                    )
                                }

                                if (canWrite) {
                                    writeChar = c
                                }

                                continue
                            }

                            if (
                                writeChar == null &&
                                canWrite
                            ) {
                                writeChar = c
                            }

                            if (
                                dataChar == null &&
                                canListen
                            ) {
                                dataChar = c

                                enableNotify(
                                    g,
                                    c
                                )
                            }
                        }
                    }

                    if (!foundTarget) {
                        Log.e(
                            TAG,
                            "121GW characteristic not found"
                        )

                        statusText.value =
                            "121GW BLE characteristic not found"

                        return@post
                    }

                    if (dataChar == null) {
                        Log.e(
                            TAG,
                            "No notification characteristic found"
                        )

                        statusText.value =
                            "No BLE data characteristic"

                        return@post
                    }

                    Log.d(
                        TAG,
                        "Data characteristic=" +
                            "${dataChar?.uuid}"
                    )

                    if (
                        writeChar != null
                    ) {
                        Log.d(
                            TAG,
                            "Write characteristic=" +
                                "${writeChar?.uuid}"
                        )
                    } else {
                        Log.w(
                            TAG,
                            "No write characteristic found"
                        )
                    }

                    statusText.value =
                        if (notificationsEnabled) {
                            "Live"
                        } else {
                            "Enabling notifications…"
                        }
                }
            }

            @Deprecated(
                "Deprecated in Java"
            )
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic
            ) {
                Log.d(
                    TAG,
                    "onCharacteristicChanged legacy " +
                        "uuid=${c.uuid} " +
                        "length=${c.value.size}"
                )

                onData(c.value)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                Log.d(
                    TAG,
                    "onCharacteristicChanged " +
                        "uuid=${c.uuid} " +
                        "length=${value.size}"
                )

                onData(value)
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                Log.d(
                    TAG,
                    "onDescriptorWrite " +
                        "uuid=${descriptor.uuid} " +
                        "status=$status"
                )

                if (
                    descriptor.uuid != CCCD
                ) {
                    return
                }

                handler.post {
                    if (
                        status ==
                        BluetoothGatt.GATT_SUCCESS
                    ) {
                        notificationsEnabled = true

                        statusText.value =
                            "Live"

                        Log.d(
                            TAG,
                            "121GW notifications ENABLED"
                        )
                    } else {
                        notificationsEnabled = false

                        statusText.value =
                            "Notification enable failed: $status"

                        Log.e(
                            TAG,
                            "CCCD write failed " +
                                "status=$status"
                        )
                    }
                }
            }

            override fun onReadRemoteRssi(
                g: BluetoothGatt,
                r: Int,
                status: Int
            ) {
                if (
                    status ==
                    BluetoothGatt.GATT_SUCCESS
                ) {
                    handler.post {
                        rssi.value = r
                    }
                } else {
                    Log.w(
                        TAG,
                        "RSSI read failed: $status"
                    )
                }
            }
        }

    private fun enableNotify(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic
    ) {
        Log.d(
            TAG,
            "Enabling notifications for ${c.uuid}"
        )

        val localResult =
            try {
                g.setCharacteristicNotification(
                    c,
                    true
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "setCharacteristicNotification failed",
                    e
                )
                false
            }

        Log.d(
            TAG,
            "setCharacteristicNotification " +
                "uuid=${c.uuid} " +
                "result=$localResult"
        )

        if (!localResult) {
            notificationsEnabled = false

            statusText.value =
                "Local notification setup failed"

            Log.e(
                TAG,
                "setCharacteristicNotification failed"
            )

            return
        }

        val descriptor =
            c.getDescriptor(CCCD)

        if (descriptor == null) {
            notificationsEnabled = false

            statusText.value =
                "CCCD descriptor missing"

            Log.e(
                TAG,
                "CCCD $CCCD not found on ${c.uuid}"
            )

            return
        }

        val enableValue =
            if (
                (c.properties and
                    BluetoothGattCharacteristic
                        .PROPERTY_INDICATE) != 0 &&
                (c.properties and
                    BluetoothGattCharacteristic
                        .PROPERTY_NOTIFY) == 0
            ) {
                BluetoothGattDescriptor
                    .ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor
                    .ENABLE_NOTIFICATION_VALUE
            }

        descriptor.value =
            enableValue

        Log.d(
            TAG,
            "Writing CCCD " +
                "uuid=${descriptor.uuid} " +
                "value=${enableValue.toHexString()}"
        )

        val writeResult =
            try {
                g.writeDescriptor(
                    descriptor
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "writeDescriptor failed",
                    e
                )
                false
            }

        Log.d(
            TAG,
            "CCCD write requested=$writeResult"
        )

        if (!writeResult) {
            notificationsEnabled = false

            statusText.value =
                "CCCD write request failed"
        } else {
            statusText.value =
                "Enabling notifications…"
        }
    }

    private fun onData(
        data: ByteArray?
    ) {
        if (
            data == null ||
            data.isEmpty()
        ) {
            Log.w(
                TAG,
                "Empty BLE notification"
            )

            return
        }

        bytesReceived.value += data.size

        Log.d(
            TAG,
            "RX ${data.size} bytes: " +
                data.toHexString()
        )

        handler.post {
            processIncoming(data)
        }
    }

    private fun processIncoming(
        data: ByteArray
    ) {
        buffer.addAll(
            data.toList()
        )

        if (buffer.size > 1024) {
            val drop =
                buffer.size - 256

            repeat(drop) {
                buffer.removeAt(0)
            }

            Log.w(
                TAG,
                "RX buffer exceeded 1024 bytes; " +
                    "dropped $drop bytes"
            )
        }

        var offset = 0

        var lastGood: PacketV2? = null

        val frameLen =
            PacketV2.LENGTH

        while (
            offset + frameLen <=
            buffer.size
        ) {
            if (
                (buffer[offset].toInt() and 0xFF) !=
                PacketV2.START
            ) {
                offset++
                continue
            }

            val frame =
                ByteArray(frameLen) {
                    buffer[offset + it]
                }

            val pkt =
                PacketV2.parse(frame)

            if (
                pkt != null &&
                pkt.checksumOK
            ) {
                lastGood = pkt

                Log.d(
                    TAG,
                    "Valid PacketV2 " +
                        "offset=$offset " +
                        "length=$frameLen"
                )

                offset += frameLen
            } else {
                Log.w(
                    TAG,
                    "Invalid PacketV2 candidate " +
                        "offset=$offset " +
                        "frame=${frame.toHexString()}"
                )

                offset++
            }
        }

        if (offset > 0) {
            repeat(
                minOf(
                    offset,
                    buffer.size
                )
            ) {
                buffer.removeAt(0)
            }
        }

        val pkt =
            lastGood ?: return

        latestPacket.value =
            pkt

        lastPacketAt =
            System.currentTimeMillis()

        communicationLost.value =
            false

        val v =
            pkt.mainValue

        liveValue.value =
            if (v.isFinite()) {
                v
            } else {
                0.0
            }

        liveUnit.value =
            pkt.mainUnit

        liveDisplay.value =
            pkt.displayString

        packetsReceived.value += 1

        Log.d(
            TAG,
            "MEASUREMENT " +
                "value=${liveDisplay.value} " +
                "unit=${liveUnit.value} " +
                "packets=${packetsReceived.value}"
        )

        when (pkt.subModeRaw) {

            110 -> {
                if (
                    pkt.subValue.isFinite()
                ) {
                    batteryVoltage.value =
                        kotlin.math.abs(
                            pkt.subValue
                        )
                }
            }

            140 -> {
                val n =
                    kotlin.math.abs(
                        pkt.subIntValue
                    )

                val hh =
                    minOf(
                        23,
                        (n shr 8) and 0xFF
                    )

                val mm =
                    minOf(
                        59,
                        n and 0xFF
                    )

                meterTime.value =
                    "%02d:%02d".format(
                        hh,
                        mm
                    )
            }
        }

        packetFlow.tryEmit(pkt)
    }
}

private object BluetoothGattDescriptorCompat {
    fun enableNotificationValue(): ByteArray =
        byteArrayOf(
            0x01,
            0x00
        )
}

private fun ByteArray.toHexString(): String =
    joinToString(" ") {
        "%02X".format(
            it.toInt() and 0xFF
        )
    }
