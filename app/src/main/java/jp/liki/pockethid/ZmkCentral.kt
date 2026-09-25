package jp.liki.pockethid

import android.Manifest
import android.bluetooth.BluetoothAdapter
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Android GATT central for two standard ZMK BLE split peripherals. */
internal class ZmkCentral(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun status(message: String)
        fun position(position: Int, pressed: Boolean)
        fun motion(x: Int, y: Int)
    }

    companion object {
        private val SERVICE = UUID.fromString("00000000-0096-7107-c967-c5cfb1c2482a")
        private val POSITIONS = UUID.fromString("00000001-0096-7107-c967-c5cfb1c2482a")
        private val INPUT = UUID.fromString("00000006-0096-7107-c967-c5cfb1c2482a")
        private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private class Peer(val device: BluetoothDevice) {
        var gatt: BluetoothGatt? = null
        var positions: BluetoothGattCharacteristic? = null
        var input: BluetoothGattCharacteristic? = null
        var state = ByteArray(16)
        var ready = false
        var x = 0
        var y = 0
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private val preferences = context.getSharedPreferences("zmk_central", Context.MODE_PRIVATE)
    private val peers = mutableMapOf<String, Peer>()
    private var active = false
    private var scanning = false
    private val stopScanLater = Runnable { stopScan() }

    fun hasPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (active) { scan(); return }
        if (!hasPermission()) { listener.status("BifrostのBluetooth権限が必要です"); return }
        if (adapter?.isEnabled != true) { listener.status("Bluetoothをオンにしてください"); return }
        active = true
        context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        preferences.getStringSet("addresses", emptySet())?.forEach { address ->
            try {
                val device = adapter.getRemoteDevice(address)
                if (device.bondState == BluetoothDevice.BOND_BONDED) connect(device)
            } catch (_: IllegalArgumentException) { /* Ignore an invalid saved address. */ }
        }
        scan()
    }

    fun scan() {
        if (!active || scanning || peers.size >= 2 || !hasPermission()) return
        val bleScanner = scanner ?: run { listener.status("BLEスキャンを開始できません"); return }
        try {
            val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
            val options = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            bleScanner.startScan(filters, options, scanCallback)
            scanning = true
            listener.status("Bifrostを検索中 (${readyCount()}/2)")
            main.postDelayed(stopScanLater, 30000)
        } catch (_: SecurityException) { listener.status("BLEスキャン権限を確認してください") }
    }

    fun stop() {
        active = false
        stopScan()
        try { context.unregisterReceiver(bondReceiver) } catch (_: IllegalArgumentException) { }
        peers.values.toList().forEach(::closePeer)
        peers.clear()
        listener.status("Bifrost接続を停止しました")
    }

    private fun stopScan() {
        main.removeCallbacks(stopScanLater)
        if (!scanning) return
        scanning = false
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
        if (active && readyCount() < 2) listener.status("Bifrost ${readyCount()}/2 接続。再検索できます")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { if (active) connect(result.device) }
        }
        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                listener.status("Bifrost検索エラー: $errorCode")
            }
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (!active || peers.containsKey(device.address) || peers.size >= 2) return
        val peer = Peer(device)
        peers[device.address] = peer
        listener.status("Bifrostに接続中 (${readyCount()}/2)")
        try {
            peer.gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (peer.gatt == null) fail(peer, "接続を開始できません")
        } catch (_: SecurityException) { fail(peer, "Bluetooth接続権限を確認してください") }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val peer = peers[device?.address] ?: return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> peer.gatt?.let { configure(peer, it) }
                BluetoothDevice.BOND_NONE -> fail(peer, "ペアリングが完了しませんでした")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                val peer = peers[gatt.device.address] ?: return@post
                if (peer.gatt !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail(peer, "Bifrostが切断されました")
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.discoverServices()) fail(peer, "サービスを取得できません")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post {
                val peer = peers[gatt.device.address] ?: return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail(peer, "ZMKサービスを取得できません"); return@post }
                val service = gatt.getService(SERVICE)
                peer.positions = service?.getCharacteristic(POSITIONS)
                peer.input = service?.getCharacteristic(INPUT)
                if (peer.positions == null) { fail(peer, "ZMKペリフェラルではありません"); return@post }
                when (peer.device.bondState) {
                    BluetoothDevice.BOND_BONDED -> configure(peer, gatt)
                    BluetoothDevice.BOND_BONDING -> listener.status("Bifrostのペアリングを承認してください")
                    else -> if (!peer.device.createBond())
                        fail(peer, "ペアリングを開始できません")
                    else listener.status("Bifrostのペアリングを承認してください")
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                val peer = peers[gatt.device.address] ?: return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail(peer, "通知を有効にできません"); return@post }
                if (descriptor.characteristic.uuid == POSITIONS && peer.input != null) {
                    if (!subscribe(gatt, peer.input!!)) fail(peer, "トラックボール通知を有効にできません")
                } else if (peer.positions?.let(gatt::readCharacteristic) != true) {
                    fail(peer, "キー状態を取得できません")
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") val value = characteristic.value
            main.post { readPosition(gatt, characteristic, value, status) }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                          value: ByteArray, status: Int) {
            main.post { readPosition(gatt, characteristic, value, status) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value
            main.post { notification(gatt, characteristic, value) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                             value: ByteArray) {
            main.post { notification(gatt, characteristic, value) }
        }
    }

    private fun configure(peer: Peer, gatt: BluetoothGatt) {
        if (peer.gatt !== gatt || peer.positions == null) return
        if (!subscribe(gatt, peer.positions!!)) fail(peer, "キー通知を有効にできません")
    }

    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val descriptor = characteristic.getDescriptor(CCC) ?: return false
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        return if (Build.VERSION.SDK_INT >= 33)
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        else writeLegacyDescriptor(gatt, descriptor)
    }

    @Suppress("DEPRECATION")
    private fun writeLegacyDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return gatt.writeDescriptor(descriptor)
    }

    private fun readPosition(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                             value: ByteArray, status: Int) {
        val peer = peers[gatt.device.address] ?: return
        if (characteristic.uuid != POSITIONS) return
        if (status != BluetoothGatt.GATT_SUCCESS || value.size != 16) { fail(peer, "キー状態が不正です"); return }
        applyPositions(peer, value)
        peer.ready = true
        val addresses = preferences.getStringSet("addresses", emptySet()).orEmpty() + peer.device.address
        preferences.edit().putStringSet("addresses", addresses).apply()
        listener.status("Bifrost ${readyCount()}/2 接続")
        if (readyCount() == 2) stopScan()
    }

    private fun notification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val peer = peers[gatt.device.address] ?: return
        when (characteristic.uuid) {
            POSITIONS -> if (value.size == 16) applyPositions(peer, value)
            INPUT -> applyInput(peer, value)
        }
    }

    private fun applyPositions(peer: Peer, value: ByteArray) {
        for (position in 0..48) {
            val old = (peer.state[position / 8].toInt() shr (position % 8)) and 1
            val next = (value[position / 8].toInt() shr (position % 8)) and 1
            if (old != next) listener.position(position, next != 0)
        }
        peer.state = value.copyOf()
    }

    private fun applyInput(peer: Peer, value: ByteArray) {
        if (value.size != 8 || value[0].toInt() != 2) return // EV_REL
        val bytes = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val code = bytes.getShort(1).toInt() and 0xffff
        val amount = bytes.getInt(3)
        when (code) {
            0 -> peer.x += amount // REL_X
            1 -> peer.y += amount // REL_Y
        }
        if (value[7].toInt() != 0 && (peer.x != 0 || peer.y != 0)) {
            listener.motion(peer.x, peer.y)
            peer.x = 0; peer.y = 0
        }
    }

    private fun fail(peer: Peer, message: String) {
        if (peers.remove(peer.device.address) !== peer) return
        closePeer(peer)
        listener.status("$message (${readyCount()}/2)")
        if (active) main.postDelayed({ scan() }, 1500)
    }

    private fun closePeer(peer: Peer) {
        applyPositions(peer, ByteArray(16))
        peer.gatt?.close()
        peer.gatt = null
    }

    private fun readyCount(): Int = peers.values.count { it.ready }
}
