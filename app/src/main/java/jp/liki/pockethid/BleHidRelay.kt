package jp.liki.pockethid

import android.Manifest
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/** Receives standard BLE HID input and optionally reads ZMK Studio's physical layout. */
internal class BleHidRelay(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun status(message: String)
        fun found(device: BluetoothDevice)
        fun keyboard(modifiers: Int, usages: Set<Int>)
        fun consumer(usage: Int)
        fun mouse(buttons: Int, x: Int, y: Int, wheel: Int)
        fun studio(layout: StudioProtocol.Layout?, bindings: List<StudioProtocol.Binding>)
    }

    companion object {
        private fun uuid(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
        private val HID = uuid("1812")
        private val REPORT_MAP = uuid("2a4b")
        private val REPORT = uuid("2a4d")
        private val BOOT_KEYBOARD = uuid("2a22")
        private val BOOT_MOUSE = uuid("2a33")
        private val REPORT_REFERENCE = uuid("2908")
        private val CCC = uuid("2902")
        private val STUDIO = UUID.fromString("00000000-0196-6107-c967-c5cfb1c2482a")
        private val STUDIO_RPC = UUID.fromString("00000001-0196-6107-c967-c5cfb1c2482a")
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var active = false
    private var pendingBond: BluetoothDevice? = null
    private var bondReceiverRegistered = false
    private var reportMap = emptyMap<Int, Int>()
    private val reportIds = mutableMapOf<BluetoothGattCharacteristic, Int>()
    private val operations = ArrayDeque<() -> Boolean>()
    private var busy = false
    private var studioCharacteristic: BluetoothGattCharacteristic? = null
    private val frames = StudioProtocol.Frames()
    private var studioLayout: StudioProtocol.Layout? = null
    private var studioBindings = emptyList<StudioProtocol.Binding>()
    private val stopScanLater = Runnable {
        stopScan()
        if (gatt == null && pendingBond == null) listener.status("BLE HID 検索が終了しました")
    }

    fun hasPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31)
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    else context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun scan() {
        if (!hasPermission()) { listener.status("BLE 検索権限が必要です"); return }
        if (adapter?.isEnabled != true) { listener.status("Bluetoothをオンにしてください"); return }
        stopScan()
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HID)).build()
            adapter.bluetoothLeScanner?.startScan(listOf(filter),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            scanning = true
            listener.status("BLE HID キーボードを検索中…")
            main.postDelayed(stopScanLater, 20000)
        } catch (_: SecurityException) { listener.status("BLE 検索権限を確認してください") }
    }

    fun stopScan() {
        main.removeCallbacks(stopScanLater)
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
    }

    fun connect(device: BluetoothDevice) {
        if (!hasPermission()) { listener.status("BLE 接続権限が必要です"); return }
        stop()
        active = true
        listener.status("${device.name ?: device.address} に接続中…")
        try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) openGatt(device)
            else {
                pendingBond = device
                context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
                bondReceiverRegistered = true
                listener.status("${device.name ?: device.address} のペアリングを承認してください")
                if (device.bondState == BluetoothDevice.BOND_NONE && !device.createBond())
                    stopWithStatus("BLE ペアリングを開始できません")
            }
        } catch (_: SecurityException) { stopWithStatus("BLE 接続権限を確認してください") }
    }

    private fun openGatt(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) stopWithStatus("BLE 接続を開始できません")
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (!active || device?.address != pendingBond?.address) return
            val bondedDevice = device ?: return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> {
                    pendingBond = null
                    context.unregisterReceiver(this); bondReceiverRegistered = false
                    try { openGatt(bondedDevice) } catch (_: SecurityException) {
                        stopWithStatus("BLE 接続権限を確認してください")
                    }
                }
                BluetoothDevice.BOND_NONE -> stopWithStatus("BLE ペアリングが完了しませんでした")
            }
        }
    }

    fun stop() {
        active = false
        stopScan()
        pendingBond = null
        if (bondReceiverRegistered) {
            try { context.unregisterReceiver(bondReceiver) } catch (_: IllegalArgumentException) { }
            bondReceiverRegistered = false
        }
        operations.clear(); busy = false
        gatt?.disconnect(); gatt?.close(); gatt = null
        reportIds.clear(); reportMap = emptyMap()
        studioCharacteristic = null; studioLayout = null; studioBindings = emptyList()
        listener.keyboard(0, emptySet())
        listener.consumer(0)
        listener.mouse(0, 0, 0, 0)
        listener.studio(null, emptyList())
    }

    private fun stopWithStatus(message: String) { stop(); listener.status(message) }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { listener.found(result.device) }
        }
        override fun onScanFailed(errorCode: Int) {
            main.post { stopWithStatus("BLE 検索エラー: $errorCode") }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, state: Int) {
            main.post {
                if (connection !== gatt || !active) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED)
                    stopWithStatus("BLE HID キーボードが切断されました ($status)")
                else if (state == BluetoothProfile.STATE_CONNECTED) {
                    listener.status("BLE HID サービスを確認中…")
                    if (!connection.discoverServices()) stopWithStatus("BLE サービスを取得できません")
                }
            }
        }

        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            main.post {
                if (connection !== gatt || !active) return@post
                val service = connection.getService(HID)
                if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                    stopWithStatus("HID over GATT サービスがありません"); return@post
                }
                studioCharacteristic = connection.getService(STUDIO)?.getCharacteristic(STUDIO_RPC)
                val map = service.getCharacteristic(REPORT_MAP)
                if (map != null) enqueue { connection.readCharacteristic(map) }
                service.characteristics.filter { it.uuid == REPORT }.forEach { report ->
                    report.getDescriptor(REPORT_REFERENCE)?.let { reference ->
                        enqueue { connection.readDescriptor(reference) }
                    }
                }
                val inputs = service.characteristics.filter { it.uuid == REPORT ||
                    it.uuid == BOOT_KEYBOARD || it.uuid == BOOT_MOUSE }
                inputs.forEach { characteristic ->
                    if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                        subscribe(connection, characteristic, false)
                }
                studioCharacteristic?.let { subscribe(connection, it, true) }
                enqueue {
                    listener.status("BLE HID 接続済み${if (studioCharacteristic != null) " / Studio 対応" else ""}")
                    studioCharacteristic?.let { rpc ->
                        main.post { if (active && connection === gatt)
                            sendStudioRequest(connection, rpc, 1, 6) }
                    }
                    false
                }
                advance()
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                          status: Int) {
            @Suppress("DEPRECATION") val value = characteristic.value
            main.post { characteristicRead(connection, characteristic, value, status) }
        }
        override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                          value: ByteArray, status: Int) {
            main.post { characteristicRead(connection, characteristic, value, status) }
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onDescriptorRead(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            @Suppress("DEPRECATION") val value = descriptor.value
            main.post { descriptorRead(connection, descriptor, value, status) }
        }
        override fun onDescriptorRead(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor,
                                      status: Int, value: ByteArray) {
            main.post { descriptorRead(connection, descriptor, value, status) }
        }
        override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (connection !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) listener.status("HID 通知の設定に失敗しました")
                busy = false; advance()
            }
        }
        override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                           status: Int) {
            main.post { if (connection === gatt) { busy = false; advance() } }
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value
            main.post { notification(connection, characteristic, value) }
        }
        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                             value: ByteArray) {
            main.post { notification(connection, characteristic, value) }
        }
    }

    private fun enqueue(operation: () -> Boolean) { operations.addLast(operation) }
    private fun advance() {
        if (busy || !active) return
        while (operations.isNotEmpty()) {
            busy = operations.removeFirst().invoke()
            if (busy) return
        }
    }

    private fun subscribe(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                          indication: Boolean) {
        val descriptor = characteristic.getDescriptor(CCC) ?: return
        enqueue {
            if (!connection.setCharacteristicNotification(characteristic, true)) return@enqueue false
            val value = if (indication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33)
                connection.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
            else writeLegacyDescriptor(connection, descriptor, value)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacyDescriptor(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor,
                                      value: ByteArray): Boolean {
        descriptor.value = value
        return connection.writeDescriptor(descriptor)
    }

    private fun characteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                   value: ByteArray, status: Int) {
        if (connection !== gatt) return
        if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == REPORT_MAP)
            reportMap = reportTypes(value)
        busy = false; advance()
    }

    private fun descriptorRead(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor,
                               value: ByteArray, status: Int) {
        if (connection !== gatt) return
        if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == REPORT_REFERENCE &&
            value.size >= 2 && value[1].toInt() == 1)
            reportIds[descriptor.characteristic] = value[0].toInt() and 255
        busy = false; advance()
    }

    private fun notification(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                             value: ByteArray) {
        if (connection !== gatt || !active) return
        if (characteristic == studioCharacteristic) {
            frames.feed(value).forEach(::studioFrame)
            return
        }
        val type = when (characteristic.uuid) {
            BOOT_KEYBOARD -> 1
            BOOT_MOUSE -> 2
            REPORT -> reportIds[characteristic]?.let(reportMap::get) ?: 0
            else -> 0
        }
        when (type) {
            1 -> if (value.size >= 8) {
                val modifiers = value[0].toInt() and 255
                val usages = value.copyOfRange(2, 8).map { it.toInt() and 255 }
                    .filter { it > 3 }.toSet()
                listener.keyboard(modifiers, usages)
            }
            2 -> if (value.size >= 3) listener.mouse(value[0].toInt() and 7,
                value[1].toInt(), value[2].toInt(), value.getOrNull(3)?.toInt() ?: 0)
            3 -> if (value.size >= 2) listener.consumer(
                (value[0].toInt() and 255) or ((value[1].toInt() and 255) shl 8))
        }
    }

    private fun sendStudioRequest(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                  id: Int, requestType: Int) {
        val bytes = StudioProtocol.request(id, requestType)
        enqueue {
            if (Build.VERSION.SDK_INT >= 33)
                connection.writeCharacteristic(characteristic, bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            else writeLegacyCharacteristic(connection, characteristic, bytes)
        }
        advance()
    }

    @Suppress("DEPRECATION")
    private fun writeLegacyCharacteristic(connection: BluetoothGatt,
                                          characteristic: BluetoothGattCharacteristic,
                                          bytes: ByteArray): Boolean {
        characteristic.value = bytes
        return connection.writeCharacteristic(characteristic)
    }

    private fun studioFrame(frame: ByteArray) {
        val response = StudioProtocol.keymapResponse(frame) ?: return
        response.second.forEach { field ->
            when (field.firstOrNull()?.toInt()) {
                6 -> studioLayout = StudioProtocol.physicalLayouts(field.copyOfRange(1, field.size))
                1 -> studioBindings = StudioProtocol.baseBindings(field.copyOfRange(1, field.size))
            }
        }
        listener.studio(studioLayout, studioBindings)
        if (response.first == 1) {
            val connection = gatt ?: return
            val characteristic = studioCharacteristic ?: return
            sendStudioRequest(connection, characteristic, 2, 1)
        }
    }

    /** Report IDs of top-level keyboard, mouse and consumer collections. */
    private fun reportTypes(map: ByteArray): Map<Int, Int> {
        val types = mutableMapOf<Int, Int>()
        var at = 0; var page = 0; var usage = 0; var depth = 0; var currentType = 0
        while (at < map.size) {
            val prefix = map[at++].toInt() and 255
            if (prefix == 0xfe) {
                if (at + 2 > map.size) break
                at += 2 + (map[at].toInt() and 255)
                continue
            }
            val size = when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
            if (at + size > map.size) break
            var value = 0
            for (i in 0 until size) value = value or ((map[at + i].toInt() and 255) shl (8 * i))
            at += size
            when (prefix and 0xfc) {
                0x04 -> page = value
                0x08 -> usage = value
                0xa0 -> {
                    if (depth == 0) currentType = when {
                        page == 1 && usage == 6 -> 1
                        page == 1 && usage == 2 -> 2
                        page == 12 && usage == 1 -> 3
                        else -> 0
                    }
                    depth++
                }
                0xc0 -> { depth = (depth - 1).coerceAtLeast(0); if (depth == 0) currentType = 0 }
                0x84 -> if (currentType != 0) types[value] = currentType
            }
        }
        return types
    }
}
