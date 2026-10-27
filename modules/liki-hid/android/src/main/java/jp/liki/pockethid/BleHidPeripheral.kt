package jp.liki.pockethid

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

/** Experimental HOGP server. Never registers BluetoothHidDevice, so HID Host stays available. */
internal class BleHidPeripheral(private val context: Context, private val changed: (String) -> Unit) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var generation = 0
    private var peer: BluetoothDevice? = null
    private val pendingConnections = linkedSetOf<BluetoothDevice>()
    private var protocol = 1
    private var suspended = false
    private var advertiseCallback: AdvertiseCallback? = null
    private var wantsAdvertising = false
    private var encrypted = false
    private val encryptedPeers = mutableSetOf<String>()
    private val subscriptionIds = mutableMapOf<BluetoothGattCharacteristic, String>()
    private val preferences = context.getSharedPreferences("ble_hid_subscriptions", Context.MODE_PRIVATE)
    private val savedSubscriptions = BondedSubscriptions(
        { preferences.getStringSet(it, emptySet()).orEmpty() },
        { address, ids -> preferences.edit().putStringSet(address, ids).apply() },
        { preferences.edit().remove(it).apply(); Unit },
    )
    private var observingPower = false
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_OFF) {
                close(); changed("Bluetoothがオフになりました。オンにしてから再試行してください")
            }
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED && peer == device &&
                intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) == BluetoothDevice.BOND_BONDED) {
                savedSubscriptions.update(device.address, true, subscriptions.mapNotNull { subscriptionIds[it] }.toSet())
            }
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED &&
                intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) == BluetoothDevice.BOND_NONE) {
                savedSubscriptions.forget(device.address)
                encryptedPeers.remove(device.address)
                if (peer == device) { encrypted = false; subscriptions.clear(); changed("PCとのペアリングが解除されました") }
            }
            if (Build.VERSION.SDK_INT >= 36 && intent.action == BluetoothDevice.ACTION_ENCRYPTION_CHANGE &&
                intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, -1) == BluetoothDevice.TRANSPORT_LE) {
                val enabled = intent.getIntExtra(BluetoothDevice.EXTRA_ENCRYPTION_STATUS, -1) == 0 &&
                    intent.getBooleanExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, false)
                if (enabled) encryptedPeers.add(device.address) else encryptedPeers.remove(device.address)
                if (peer == device) {
                    encrypted = enabled
                    Log.d("LikiBle", "LE encryption=$enabled subscriptions=${subscriptions.size}")
                    if (!enabled) clearWaitingReports()
                    changed(connectionMessage())
                }
            }
        }
    }
    private val subscriptions = mutableSetOf<BluetoothGattCharacteristic>()
    private val values = mutableMapOf<BluetoothGattCharacteristic, ByteArray>()
    private val inputs = mutableMapOf<Int, BluetoothGattCharacteristic>()
    private lateinit var bootKeyboard: BluetoothGattCharacteristic
    private lateinit var bootMouse: BluetoothGattCharacteristic
    private lateinit var output: BluetoothGattCharacteristic
    private lateinit var bootOutput: BluetoothGattCharacteristic
    private lateinit var protocolMode: BluetoothGattCharacteristic
    private lateinit var controlPoint: BluetoothGattCharacteristic
    private lateinit var battery: BluetoothGattCharacteristic
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private data class Notification(val characteristic: BluetoothGattCharacteristic, val data: ByteArray)
    private val notifications = ArrayDeque<Notification>()
    private var sending = false
    private val notificationTimeout = Runnable { fail("BLE送信がタイムアウトしました。ペアリング待機から再試行してください") }
    var isReady = false
        private set
    val connectedDevice: BluetoothDevice? get() = peer?.takeIf { encrypted && subscriptions.isNotEmpty() && !suspended }

    fun start() {
        if (isReady || server != null) return
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) { changed("Bluetoothをオンにしてください"); return }
        if (adapter.bluetoothLeAdvertiser == null) { changed("この端末はBLE Peripheralに対応していません"); return }
        if (!observingPower) {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= 36) addAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(powerReceiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(powerReceiver, filter)
            observingPower = true
        }
        val bonded = adapter.bondedDevices.map { it.address }.toSet()
        preferences.all.keys.filter { it !in bonded }.forEach { savedSubscriptions.forget(it) }
        val epoch = ++generation
        buildServices()
        try {
            server = manager.openGattServer(context, callback(epoch))
            if (server == null) { fail("BLE GATTサーバーを開始できませんでした"); return }
            changed("BLE HIDサービスを準備中…")
            addNextService()
        } catch (error: RuntimeException) { fail("BLE HIDを開始できません: ${error.message}") }
    }

    private fun addNextService() {
        if (pendingServices.isEmpty()) {
            isReady = true
            changed("準備完了。BLEペアリング待機を押してください")
            acceptPendingConnections()
            startAdvertising()
            return
        }
        if (server?.addService(pendingServices.removeFirst()) != true) fail("BLE HIDサービスを登録できませんでした")
    }

    fun advertise() {
        Log.d("LikiBle", "advertise ready=$isReady peer=${peer != null} active=${advertiseCallback != null}")
        if (peer != null) { changed("PCとのBLE接続は開始済みです"); return }
        wantsAdvertising = true
        if (!isReady) { start(); return }
        acceptPendingConnections()
        startAdvertising()
    }

    private fun acceptPendingConnections() {
        if (!isReady || !wantsAdvertising) return
        // cancelConnection may leave an OS-owned link alive, with no new connected callback.
        val linked = manager?.getConnectedDevices(BluetoothProfile.GATT_SERVER).orEmpty()
        encryptedPeers.retainAll(linked.map { it.address }.toSet())
        val connections = (pendingConnections + linked).toList()
        pendingConnections.clear()
        connections.forEach { acceptConnection(it) }
    }

    private fun acceptConnection(device: BluetoothDevice, hidRequest: Boolean = false): Boolean {
        if (!isReady || !wantsAdvertising) return false
        if (peer != null) return peer == device
        val restored = savedSubscriptions.restore(device.address, device.bondState == BluetoothDevice.BOND_BONDED)
        // Android also reports links to keyboards/mice owned by its HID Host. A link alone
        // does not identify our client; require a HID request or a previous subscription.
        if (!hidRequest && restored.isEmpty()) return false
        resetPeer(); peer = device; stopAdvertising()
        encrypted = device.address in encryptedPeers
        subscriptions.addAll(subscriptionIds.filterValues { it in restored }.keys)
        Log.d("LikiBle", "restored subscriptions=${subscriptions.size} encrypted=$encrypted")
        changed(connectionMessage())
        return true
    }

    private fun acceptRequest(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!isReady || !wantsAdvertising || (peer != null && peer != device)) return false
        // Device information and battery reads must not reserve the HID connection.
        return characteristic.service.uuid != uuid(0x1812) || acceptConnection(device, hidRequest = true)
    }

    private fun startAdvertising() {
        if (!wantsAdvertising || !isReady || advertiseCallback != null || peer != null) return
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) { changed("BLE公開を利用できません。Bluetoothを確認してください"); return }
        val epoch = generation
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { handler.post {
                if (epoch == generation && advertiseCallback === this) {
                    Log.d("LikiBle", "advertising started")
                    changed("BLE公開中です。PCのBluetooth設定からこのスマホを追加してください")
                }
            } }
            override fun onStartFailure(errorCode: Int) { handler.post {
                if (epoch == generation && advertiseCallback === this) {
                    advertiseCallback = null; wantsAdvertising = false
                    changed("BLE公開を開始できませんでした（エラー $errorCode）")
                }
            } }
        }
        advertiseCallback = callback
        try {
            advertiser.startAdvertising(AdvertiseSettings.Builder().setConnectable(true)
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTimeout(0).build(),
                AdvertiseData.Builder().addServiceUuid(ParcelUuid(uuid(0x1812))).build(),
                AdvertiseData.Builder().setIncludeDeviceName(true).build(), callback)
        } catch (error: RuntimeException) { fail("BLE公開を開始できません: ${error.message}") }
    }

    private fun stopAdvertising() {
        advertiseCallback?.let { callback ->
            try { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) } catch (_: RuntimeException) { }
        }
        advertiseCallback = null
    }

    private fun characteristic(service: BluetoothGattService, id: Int, properties: Int, permissions: Int, data: ByteArray): BluetoothGattCharacteristic {
        val result = BluetoothGattCharacteristic(uuid(id), properties, permissions)
        service.addCharacteristic(result); values[result] = data
        return result
    }

    private fun input(service: BluetoothGattService, id: Int, data: ByteArray, report: Int? = null): BluetoothGattCharacteristic {
        val result = characteristic(service, id, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED, data)
        result.addDescriptor(BluetoothGattDescriptor(uuid(0x2902), BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
            BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED))
        subscriptionIds[result] = if (report != null) "report:$report" else "boot:$id"
        if (report != null) reference(result, report, 1)
        return result
    }

    @Suppress("DEPRECATION")
    private fun reference(characteristic: BluetoothGattCharacteristic, id: Int, type: Int) {
        characteristic.addDescriptor(BluetoothGattDescriptor(uuid(0x2908), BluetoothGattDescriptor.PERMISSION_READ).apply {
            value = byteArrayOf(id.toByte(), type.toByte())
        })
    }

    private fun buildServices() {
        values.clear(); inputs.clear(); subscriptionIds.clear(); pendingServices.clear()
        val hid = BluetoothGattService(uuid(0x1812), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        characteristic(hid, 0x2a4a, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ,
            byteArrayOf(0x11, 0x01, 0, 0x02)) // HID 1.11, normally connectable; no remote wake.
        characteristic(hid, 0x2a4b, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            HidReports.DESCRIPTOR)
        protocolMode = characteristic(hid, 0x2a4e, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED, byteArrayOf(1))
        controlPoint = characteristic(hid, 0x2a4c, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED, byteArrayOf(1))
        inputs[HidReports.KEYBOARD_ID] = input(hid, 0x2a4d, HidReports.keyboard(0, 0), HidReports.KEYBOARD_ID)
        inputs[HidReports.MOUSE_ID] = input(hid, 0x2a4d, HidReports.mouse(0, 0, 0, 0), HidReports.MOUSE_ID)
        inputs[HidReports.CONSUMER_ID] = input(hid, 0x2a4d, HidReports.consumer(0), HidReports.CONSUMER_ID)
        val outputProperties = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        val outputPermissions = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        output = characteristic(hid, 0x2a4d, outputProperties, outputPermissions, byteArrayOf(0))
        reference(output, HidReports.KEYBOARD_ID, 2)
        bootKeyboard = input(hid, 0x2a22, HidReports.keyboard(0, 0))
        bootMouse = input(hid, 0x2a33, byteArrayOf(0, 0, 0))
        bootOutput = characteristic(hid, 0x2a32, outputProperties, outputPermissions, byteArrayOf(0))
        val bas = BluetoothGattService(uuid(0x180f), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        battery = characteristic(bas, 0x2a19, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ, byteArrayOf(100))
        val info = BluetoothGattService(uuid(0x180a), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        characteristic(info, 0x2a29, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ, "Liki".toByteArray())
        characteristic(info, 0x2a24, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ, "Liki BLE HID".toByteArray())
        // Bluetooth SIG vendor 0xffff is reserved for internal testing; this branch is experimental.
        characteristic(info, 0x2a50, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ,
            byteArrayOf(1, -1, -1, 1, 0, 0, 1))
        pendingServices.addAll(listOf(hid, bas, info))
    }

    private fun callback(epoch: Int) = object : BluetoothGattServerCallback() {
        private fun dispatch(action: () -> Unit) { handler.post { if (epoch == generation) {
            try { action() } catch (error: RuntimeException) { fail("BLE HIDエラー: ${error.message}") }
        } } }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) = dispatch {
            Log.d("LikiBle", "service=${service.uuid} handle=${service.instanceId} status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) addNextService() else fail("BLE HIDサービス登録に失敗しました（$status）")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = dispatch {
            Log.d("LikiBle", "connection state=$newState status=$status bonded=${device.bondState == BluetoothDevice.BOND_BONDED} ready=$isReady waiting=$wantsAdvertising")
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                encryptedPeers.remove(device.address)
                pendingConnections.remove(device)
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // An OS-owned BLE link can already exist when this server is opened.
                if (!isReady || !wantsAdvertising) {
                    pendingConnections.add(device)
                    Log.d("LikiBle", "connection deferred until ready and waiting")
                    return@dispatch
                }
                acceptConnection(device)
            } else if (peer == device && (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS)) {
                encryptedPeers.remove(device.address)
                resetPeer(); changed("BLE接続が切断されました")
                startAdvertising()
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) = dispatch {
            if (!acceptRequest(device, characteristic)) { reply(device, requestId, BluetoothGatt.GATT_FAILURE); return@dispatch }
            if (characteristic.permissions and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED != 0) markEncrypted()
            val data = when (characteristic) {
                battery -> byteArrayOf(context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100).toByte())
                protocolMode -> byteArrayOf(protocol.toByte())
                else -> values[characteristic]
            }
            read(device, requestId, offset, data)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) = dispatch {
            @Suppress("DEPRECATION")
            val data = when (descriptor.uuid) {
                uuid(0x2902) -> byteArrayOf(if (descriptor.characteristic in subscriptions) 1 else 0, 0)
                uuid(0x2908) -> descriptor.value
                else -> null
            }
            if (acceptRequest(device, descriptor.characteristic)) {
                if (descriptor.uuid == uuid(0x2902)) markEncrypted()
                read(device, requestId, offset, data)
            } else reply(device, requestId, BluetoothGatt.GATT_FAILURE)
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) = dispatch {
            val status = when {
                !acceptRequest(device, descriptor.characteristic) -> BluetoothGatt.GATT_FAILURE
                preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                descriptor.uuid != uuid(0x2902) -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                !value.contentEquals(byteArrayOf(0, 0)) && !value.contentEquals(byteArrayOf(1, 0)) -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                else -> BluetoothGatt.GATT_SUCCESS
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                markEncrypted()
                if (value[0].toInt() == 1) subscriptions.add(descriptor.characteristic) else subscriptions.remove(descriptor.characteristic)
                savedSubscriptions.update(device.address, device.bondState == BluetoothDevice.BOND_BONDED,
                    subscriptions.mapNotNull { subscriptionIds[it] }.toSet())
                Log.d("LikiBle", "CCCD ${subscriptionIds[descriptor.characteristic]} enabled=${value[0]} count=${subscriptions.size}")
                if (subscriptions.isEmpty()) { clearWaitingReports(); resetReports() }
            }
            if (responseNeeded) reply(device, requestId, status)
            if (status == BluetoothGatt.GATT_SUCCESS) changed(if (connectedDevice != null) "BLE HID接続中" else "PCのBLE HID購読を待っています…")
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) = dispatch {
            val status = when {
                !acceptRequest(device, characteristic) -> BluetoothGatt.GATT_FAILURE
                preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                characteristic !in listOf(output, bootOutput, protocolMode, controlPoint) -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                value.size != 1 -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                (characteristic == protocolMode || characteristic == controlPoint) && value[0].toInt() !in 0..1 -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                else -> BluetoothGatt.GATT_SUCCESS
            }
            if (status == BluetoothGatt.GATT_SUCCESS) markEncrypted()
            if (status == BluetoothGatt.GATT_SUCCESS) when (characteristic) {
                protocolMode -> { protocol = value[0].toInt(); clearWaitingReports(); resetReports() }
                controlPoint -> { suspended = value[0].toInt() == 0; clearWaitingReports(); resetReports()
                    changed(connectionMessage()) }
                else -> { values[output] = value.copyOf(); values[bootOutput] = value.copyOf() }
            }
            if (responseNeeded) reply(device, requestId, status)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = dispatch {
            reply(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = dispatch {
            if (device != peer || !sending) return@dispatch
            handler.removeCallbacks(notificationTimeout); sending = false
            if (status != BluetoothGatt.GATT_SUCCESS) fail("BLE入力を送信できませんでした（$status）") else drain()
        }
    }

    private fun reply(device: BluetoothDevice, request: Int, status: Int, offset: Int = 0, data: ByteArray? = null) {
        server?.sendResponse(device, request, status, offset, data)
    }

    private fun read(device: BluetoothDevice, request: Int, offset: Int, data: ByteArray?) {
        when {
            data == null -> reply(device, request, BluetoothGatt.GATT_READ_NOT_PERMITTED)
            offset < 0 || offset > data.size -> reply(device, request, BluetoothGatt.GATT_INVALID_OFFSET)
            else -> reply(device, request, BluetoothGatt.GATT_SUCCESS, offset, data.copyOfRange(offset, data.size))
        }
    }

    fun sendReport(device: BluetoothDevice, id: Int, data: ByteArray): Boolean {
        if (device != connectedDevice) return false
        val characteristic = if (protocol == 0) when (id) {
            HidReports.KEYBOARD_ID -> bootKeyboard
            HidReports.MOUSE_ID -> bootMouse
            else -> return false
        } else inputs[id] ?: return false
        val payload = if (protocol == 0 && id == HidReports.MOUSE_ID) data.copyOf(3) else data.copyOf()
        values[inputs[id] ?: return false] = data.copyOf()
        if (id == HidReports.KEYBOARD_ID) values[bootKeyboard] = data.copyOf()
        if (id == HidReports.MOUSE_ID) values[bootMouse] = data.copyOf(3)
        if (characteristic !in subscriptions) return false
        if (notifications.size >= 256) { fail("BLE送信が追いつかないため切断しました。再接続してください"); return false }
        notifications.addLast(Notification(characteristic, payload)); drain()
        return true
    }

    @Suppress("DEPRECATION")
    private fun drain() {
        if (sending) return
        val device = connectedDevice ?: return
        val gatt = server ?: return
        while (notifications.isNotEmpty()) {
            val next = notifications.removeFirst()
            if (next.characteristic !in subscriptions) continue
            sending = true
            val success = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.notifyCharacteristicChanged(device, next.characteristic, false, next.data) == BluetoothStatusCodes.SUCCESS
                } else {
                    next.characteristic.value = next.data
                    gatt.notifyCharacteristicChanged(device, next.characteristic, false)
                }
            } catch (error: RuntimeException) { fail("BLE入力の送信に失敗しました: ${error.message}"); return }
            if (!success) { fail("BLE入力の送信を開始できませんでした。再接続してください"); return }
            handler.postDelayed(notificationTimeout, 5000)
            return
        }
    }

    private fun clearWaitingReports() { notifications.clear() }

    // Android only delivers access to these encrypted attributes after link encryption succeeds.
    // This also confirms security on Android versions without ACTION_ENCRYPTION_CHANGE.
    private fun markEncrypted() {
        peer?.let { encryptedPeers.add(it.address) }
        if (encrypted) return
        encrypted = true
        if (connectedDevice != null) changed(connectionMessage())
    }

    private fun connectionMessage(): String = when {
        !encrypted -> "PCとのBLE暗号化を待っています…"
        suspended -> "PCがBLE HIDを一時停止しました"
        connectedDevice != null -> "BLE HID接続中"
        else -> "PCのBLE HID購読を待っています…"
    }

    fun releaseInputs() {
        clearWaitingReports()
        connectedDevice?.let { device ->
            sendReport(device, HidReports.KEYBOARD_ID, HidReports.keyboard(0, 0))
            sendReport(device, HidReports.CONSUMER_ID, HidReports.consumer(0))
            sendReport(device, HidReports.MOUSE_ID, HidReports.mouse(0, 0, 0, 0))
        }
    }

    private fun resetReports() {
        inputs[HidReports.KEYBOARD_ID]?.let { values[it] = HidReports.keyboard(0, 0) }
        inputs[HidReports.MOUSE_ID]?.let { values[it] = HidReports.mouse(0, 0, 0, 0) }
        inputs[HidReports.CONSUMER_ID]?.let { values[it] = HidReports.consumer(0) }
        if (::bootKeyboard.isInitialized) values[bootKeyboard] = HidReports.keyboard(0, 0)
        if (::bootMouse.isInitialized) values[bootMouse] = byteArrayOf(0, 0, 0)
        if (::output.isInitialized) values[output] = byteArrayOf(0)
        if (::bootOutput.isInitialized) values[bootOutput] = byteArrayOf(0)
    }

    private fun resetPeer() {
        peer = null; encrypted = false; subscriptions.clear(); protocol = 1; suspended = false
        notifications.clear(); sending = false; handler.removeCallbacks(notificationTimeout); resetReports()
    }

    fun disconnect() {
        wantsAdvertising = false; stopAdvertising()
        pendingConnections.toList().forEach { server?.cancelConnection(it) }
        pendingConnections.clear()
        val device = peer
        // Keep verified link security until Android reports disconnection/encryption loss.
        resetPeer()
        if (device != null) server?.cancelConnection(device)
        changed("切断しました。BLEペアリング待機から再接続できます")
    }

    fun close() {
        ++generation; wantsAdvertising = false; stopAdvertising()
        pendingConnections.clear()
        encryptedPeers.clear()
        val device = peer
        resetPeer(); isReady = false; pendingServices.clear()
        try { if (device != null) server?.cancelConnection(device) } catch (_: RuntimeException) { }
        try { server?.close() } catch (_: RuntimeException) { } finally { server = null }
        if (observingPower) { context.unregisterReceiver(powerReceiver); observingPower = false }
    }

    private fun fail(message: String) { close(); changed(message) }
    private fun uuid(value: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(value))
}
