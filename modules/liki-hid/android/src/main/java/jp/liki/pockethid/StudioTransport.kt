package jp.liki.pockethid

import android.bluetooth.*
import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.util.Log
import java.util.UUID

/** Connect only to a bonded device matching a currently attached external keyboard. No BLE scan. */
internal class StudioTransport(private val context: Context, private val emit: (Map<String, Any?>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val serviceId = UUID.fromString("00000000-0196-6107-c967-c5cfb1c2482a")
    private val rpcId = UUID.fromString("00000001-0196-6107-c967-c5cfb1c2482a")
    private val cccId = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var enabled = false
    private var gatt: BluetoothGatt? = null
    private var rpc: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<ByteArray>()
    private var writing = false
    private var resettingIndications = false
    private var awaitingMtu = false
    private var requestId: Int? = null
    private var requestTimeout: Runnable? = null
    private var requestReceivedBytes = 0
    private val timeout = Runnable { fail("Studioへの接続がタイムアウトしました") }
    private fun trace(message: String) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d("LikiStudio", message)
    }
    private fun state(message: String, ready: Boolean = false) { emit(mapOf("studioState" to mapOf("message" to message, "ready" to ready))) }
    fun enable(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) find() else { disconnect(); state("") }
    }
    fun retry() { disconnect(); if (enabled) find() }
    fun inputDevicesChanged() { if (enabled) find(onlyIfChanged = true) }
    private fun disconnect() {
        main.removeCallbacks(timeout)
        requestTimeout?.let(main::removeCallbacks); requestTimeout = null; requestId = null
        val old = gatt; gatt = null; rpc = null; queue.clear(); writing = false; resettingIndications = false; awaitingMtu = false
        old?.disconnect(); old?.close()
    }
    private fun fail(message: String) { disconnect(); state(message) }
    private fun find(onlyIfChanged: Boolean = false) {
        try {
            val input = context.getSystemService(InputManager::class.java)
            val names = input.inputDeviceIds.map(input::getInputDevice).filterNotNull()
                .filter { !it.isVirtual && it.supportsSource(InputDevice.SOURCE_KEYBOARD) }
                .map { it.name }.toSet()
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            val devices = adapter?.bondedDevices.orEmpty().filter { device ->
                device.type != BluetoothDevice.DEVICE_TYPE_CLASSIC && names.any { matchesStudioKeyboardName(it, device.name) }
            }
            if (onlyIfChanged) {
                if (gatt != null && gatt?.device?.address == devices.singleOrNull()?.address) return
                disconnect()
            }
            if (devices.size != 1) {
                state(if (devices.isEmpty()) "接続済みのBLEキーボードが見つかりません。接続後に再取得してください" else "BLEキーボードを1台だけ接続して再取得してください")
                return
            }
            state("ZMK Studioへ接続中…")
            gatt = devices.single().connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            main.postDelayed(timeout, 15000)
        } catch (_: SecurityException) { fail("Studio取得にはBluetooth接続権限が必要です") }
    }
    private val callback = object : BluetoothGattCallback() {
        private fun current(source: BluetoothGatt, work: () -> Unit) { main.post { if (gatt === source) work() } }
        override fun onConnectionStateChange(source: BluetoothGatt, status: Int, newState: Int) = current(source) {
            trace("connection state=$newState status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!source.discoverServices()) fail("Studioサービスを取得できません")
            } else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) fail("Studio接続が切れました。再取得してください")
        }
        override fun onServicesDiscovered(source: BluetoothGatt, status: Int) = current(source) {
            val characteristic = source.getService(serviceId)?.getCharacteristic(rpcId)
            val descriptor = characteristic?.getDescriptor(cccId)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null || descriptor == null) { fail("このキーボードからStudioサービスを取得できません"); return@current }
            rpc = characteristic
            trace("service ready properties=${characteristic.properties}")
            awaitingMtu = true
            state("Studioの受信サイズを確認中…")
            if (!source.requestMtu(247)) fail("Studioの受信サイズを設定できません")
        }
        override fun onMtuChanged(source: BluetoothGatt, mtu: Int, status: Int) = current(source) {
            trace("MTU=$mtu status=$status")
            if (!awaitingMtu) return@current
            awaitingMtu = false
            if (status != BluetoothGatt.GATT_SUCCESS || mtu < 30) {
                fail("Studioの受信サイズを確保できません（MTU=$mtu、status=$status）"); return@current
            }
            val characteristic = rpc ?: return@current
            val descriptor = characteristic.getDescriptor(cccId) ?: return@current
            if (!source.setCharacteristicNotification(characteristic, true)) { fail("Studio通知を開始できません"); return@current }
            // The official Studio client stops notifications before subscribing again;
            // a retained CCC subscription otherwise loses indications on reconnect.
            resettingIndications = true
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (!source.writeDescriptor(descriptor)) fail("Studio通知を開始できません")
        }
        override fun onDescriptorWrite(source: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = current(source) {
            trace("subscription reset=$resettingIndications status=$status")
            if (descriptor.uuid != cccId) return@current
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Studio通知を開始できません（status=$status）"); return@current }
            if (resettingIndications) {
                resettingIndications = false
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                @Suppress("DEPRECATION")
                if (!source.writeDescriptor(descriptor)) fail("Studio通知を開始できません")
            } else { main.removeCallbacks(timeout); state("", true) }
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value?.clone() ?: return
            current(source) {
                requestReceivedBytes += bytes.size
                trace("received bytes=${bytes.size}"); emit(mapOf("studioBytes" to bytes.map { it.toInt() and 255 }))
            }
        }
        override fun onCharacteristicWrite(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = current(source) {
            trace("write completed status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) fail("Studioへの送信に失敗しました") else { writing = false; next() }
        }
    }
    fun write(bytes: List<Int>, id: Int, label: String) {
        check(gatt != null && rpc != null) { "Studioが接続されていません" }
        check(requestId == null) { "Studioの通信が処理中です" }
        require(bytes.size <= 4096 && queue.size < 256)
        requestId = id; requestReceivedBytes = 0
        val deadline = Runnable {
            if (requestId == id) fail("Studioの応答がありません（$label、受信${requestReceivedBytes}バイト）。再取得してください")
        }
        requestTimeout = deadline; main.postDelayed(deadline, 8000)
        bytes.chunked(20).forEach { queue.addLast(it.map(Int::toByte).toByteArray()) }
        next()
    }
    fun complete(id: Int) {
        check(requestId == id) { "Studio接続が変更されました" }
        requestTimeout?.let(main::removeCallbacks); requestTimeout = null; requestId = null
    }
    @Suppress("DEPRECATION")
    private fun next() {
        val connection = gatt ?: return; val characteristic = rpc ?: return
        if (writing || queue.isEmpty()) return
        writing = true; characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = queue.removeFirst()
        trace("write bytes=${characteristic.value.size}")
        if (!connection.writeCharacteristic(characteristic)) fail("Studioへの送信に失敗しました")
    }
    fun close() { enabled = false; disconnect() }
}

// Android appends the HID collection name to ZMK's Bluetooth device name.
internal fun matchesStudioKeyboardName(inputName: String, bluetoothName: String?): Boolean =
    !bluetoothName.isNullOrBlank() && (inputName == bluetoothName || inputName == "$bluetoothName Keyboard")
