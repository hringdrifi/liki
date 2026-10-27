package jp.liki.pockethid

import android.bluetooth.*
import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import java.util.UUID

/** Liki's optional layer service, independent of Studio and HID report forwarding. */
internal class LikiLayerTransport(private val context: Context, private val emit: (Map<String, Any?>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val serviceId = UUID.fromString("6c696b69-0000-4000-8000-000000000001")
    private val layerId = UUID.fromString("6c696b69-0000-4000-8000-000000000002")
    private val cccId = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var enabled = false
    private var gatt: BluetoothGatt? = null
    private val timeout = Runnable { fail("ZMKレイヤー連携がタイムアウトしました") }
    private val reconnect = Runnable { if (enabled) find() }
    private fun state(message: String = "", bytes: ByteArray? = null) {
        emit(mapOf("zmkLayer" to mapOf("message" to message, "bytes" to bytes?.map { it.toInt() and 255 })))
    }
    fun enable(value: Boolean) {
        if (enabled == value) return
        enabled = value
        disconnect(); state()
        if (value) find()
    }
    fun retry() { disconnect(); state(); if (enabled) find() }
    fun inputDevicesChanged() { if (enabled) find() }
    private fun disconnect() {
        main.removeCallbacks(timeout); main.removeCallbacks(reconnect)
        val old = gatt; gatt = null
        old?.disconnect(); old?.close()
    }
    private fun fail(message: String, retry: Boolean = true) {
        disconnect(); state(message)
        if (enabled && retry) main.postDelayed(reconnect, 5000)
    }
    private fun find() {
        try {
            val input = context.getSystemService(InputManager::class.java)
            val names = input.inputDeviceIds.map(input::getInputDevice).filterNotNull()
                .filter { !it.isVirtual && it.supportsSource(InputDevice.SOURCE_KEYBOARD) }.map { it.name }
            val devices = context.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
                .filter { device -> device.type != BluetoothDevice.DEVICE_TYPE_CLASSIC && names.any { matchesStudioKeyboardName(it, device.name) } }
            val target = devices.singleOrNull()
            if (gatt != null && gatt?.device?.address == target?.address) return
            disconnect(); state()
            if (target == null) { fail(if (devices.isEmpty()) "接続済みのBLEキーボードがありません" else "BLEキーボードを1台だけ接続してください"); return }
            gatt = target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            main.postDelayed(timeout, 15000)
        } catch (_: SecurityException) { fail("ZMKレイヤー連携にはBluetooth接続権限が必要です", retry = false) }
    }
    private val callback = object : BluetoothGattCallback() {
        private fun current(source: BluetoothGatt, work: () -> Unit) { main.post { if (gatt === source) work() } }
        override fun onConnectionStateChange(source: BluetoothGatt, status: Int, newState: Int) = current(source) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!source.discoverServices()) fail("ZMKレイヤーサービスを取得できません")
            } else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) fail("ZMKレイヤー接続が切れました")
        }
        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(source: BluetoothGatt, status: Int) = current(source) {
            val layer = source.getService(serviceId)?.getCharacteristic(layerId)
            val ccc = layer?.getDescriptor(cccId)
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("ZMKレイヤーサービスを取得できません"); return@current }
            if (layer == null || ccc == null) { fail("ファームにzmk-feature-likiが必要です", retry = false); return@current }
            if (!source.setCharacteristicNotification(layer, true)) { fail("ZMKレイヤー通知を開始できません"); return@current }
            ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!source.writeDescriptor(ccc)) fail("ZMKレイヤー通知を開始できません")
        }
        override fun onDescriptorWrite(source: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = current(source) {
            if (descriptor.characteristic.uuid != layerId) return@current
            if (status != BluetoothGatt.GATT_SUCCESS || !source.readCharacteristic(descriptor.characteristic)) fail("ZMKの現在レイヤーを取得できません")
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val bytes = characteristic.value?.clone()
            current(source) {
                if (characteristic.uuid != layerId) return@current
                if (status != BluetoothGatt.GATT_SUCCESS || bytes == null) { fail("ZMKの現在レイヤーを取得できません"); return@current }
                main.removeCallbacks(timeout); state(bytes = bytes)
            }
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value?.clone() ?: return
            current(source) { if (characteristic.uuid == layerId) state(bytes = bytes) }
        }
    }
    fun close() { enabled = false; disconnect(); state() }
}
