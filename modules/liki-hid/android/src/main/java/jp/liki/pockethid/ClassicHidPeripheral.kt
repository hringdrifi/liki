package jp.liki.pockethid

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context

/** Classic HID transport restored from Liki's original Android implementation. */
internal class ClassicHidPeripheral(private val context: Context, private val changed: (String) -> Unit) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var hid: BluetoothHidDevice? = null
    var connectedDevice: BluetoothDevice? = null
        private set
    private var connectingDevice: BluetoothDevice? = null
    private var cancellingDevice: BluetoothDevice? = null
    private var registered = false
    private var registering = false
    private var requesting = false
    private var generation = 0
    private var bootMode = false
    private var callback: BluetoothHidDevice.Callback? = null
    val isReady get() = registered
    val isConnecting get() = connectingDevice != null
    val isCancelling get() = cancellingDevice != null

    fun pairedDevices(): List<BluetoothDevice> = adapter?.bondedDevices.orEmpty()
        .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" })

    fun start() {
        if (hid != null) { register(); return }
        if (requesting) return
        val bluetooth = adapter ?: run { changed("この端末はBluetoothに対応していません"); return }
        val epoch = ++generation
        requesting = true
        changed("Classic HIDを準備中…")
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (epoch != generation) { bluetooth.closeProfileProxy(profile, proxy); return }
                requesting = false; hid = proxy as BluetoothHidDevice
                callback = createCallback(epoch); register()
            }
            override fun onServiceDisconnected(profile: Int) {
                if (epoch != generation) return
                hid = null; connectedDevice = null; connectingDevice = null; cancellingDevice = null
                registered = false; registering = false; requesting = false
                changed("Classic HIDサービスが切断されました")
            }
        }
        if (!bluetooth.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)) {
            requesting = false; changed("この端末ではClassic HIDを利用できません")
        }
    }

    private fun register() {
        val service = hid ?: return
        if (registered || registering) return
        val sdp = BluetoothHidDeviceAppSdpSettings("Liki", "Keyboard and trackpad", "Liki",
            BluetoothHidDevice.SUBCLASS1_COMBO, HidReports.DESCRIPTOR)
        registering = service.registerApp(sdp, null, null, context.mainExecutor, callback!!)
        if (!registering) {
            cancellingDevice = null
            changed("Classic HID登録を開始できませんでした")
        }
    }

    private fun createCallback(epoch: Int) = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            if (epoch != generation) return
            registered = isRegistered; registering = false
            if (cancellingDevice != null) {
                if (!registered) register()
                else { cancellingDevice = null; changed("接続をキャンセルしました") }
                return
            }
            if (!registered) {
                connectedDevice = null; connectingDevice = null
                changed("Classic HID登録が解除されました。画面を開き直して再試行してください")
            } else changed("準備完了。接続先のPCを選択してください")
        }
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (epoch != generation) return
            if (cancellingDevice == device) {
                if (state == BluetoothProfile.STATE_CONNECTED) hid?.disconnect(device)
                return
            }
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectingDevice = null; connectedDevice = device; bootMode = false
                    changed("Classic HID接続中: ${device.name ?: "PC"}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val failed = connectingDevice == device
                    if (failed) connectingDevice = null
                    if (connectedDevice == device) connectedDevice = null
                    changed(if (failed) "接続できませんでした。再試行できます" else "切断されました")
                }
                BluetoothProfile.STATE_CONNECTING -> { connectingDevice = device; changed("PCへ接続中…") }
            }
        }
        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            if (epoch == generation) bootMode = protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE
        }
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            if (epoch != generation) return
            val service = hid ?: return
            val data = when {
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && (id.toInt() == HidReports.KEYBOARD_ID || (bootMode && id.toInt() == 0)) -> HidReports.keyboard(0, 0)
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && id.toInt() == HidReports.MOUSE_ID -> HidReports.mouse(0, 0, 0, 0)
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && id.toInt() == HidReports.CONSUMER_ID -> HidReports.consumer(0)
                type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id.toInt() == HidReports.KEYBOARD_ID -> byteArrayOf(0)
                else -> { service.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM); return }
            }
            service.replyReport(device, type, id, data)
        }
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            if (epoch != generation) return
            hid?.reportError(device, if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && (id.toInt() == HidReports.KEYBOARD_ID || (bootMode && id.toInt() == 0)))
                BluetoothHidDevice.ERROR_RSP_SUCCESS else BluetoothHidDevice.ERROR_RSP_INVALID_PARAM)
        }
        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            if (epoch != generation) return
            connectedDevice = null; connectingDevice = null
            changed("PC側でペアリングが解除されました")
        }
    }

    fun connect(address: String) {
        val service = hid
        check(registered && service != null) { "Classic HIDの準備が完了していません" }
        check(!isConnecting && !isCancelling && connectedDevice == null) { "接続処理中です" }
        val device = pairedDevices().firstOrNull { it.address == address } ?: error("ペアリング済みPCを選択してください")
        connectingDevice = device
        if (service.connect(device)) changed("PCへ接続中…")
        else { connectingDevice = null; changed("接続を開始できませんでした。PC側のBluetooth設定を確認してください") }
    }
    fun cancelConnection() {
        val device = connectingDevice ?: return
        check(hid?.unregisterApp() == true) { "接続をキャンセルできませんでした" }
        connectingDevice = null; cancellingDevice = device; registered = false
        changed("接続をキャンセル中…")
    }
    fun sendReport(device: BluetoothDevice, id: Int, data: ByteArray) {
        if (device != connectedDevice) return
        if (!bootMode) hid?.sendReport(device, id, data)
        else when (id) {
            HidReports.KEYBOARD_ID -> hid?.sendReport(device, 0, data)
            HidReports.MOUSE_ID -> hid?.sendReport(device, 0, data.copyOf(3))
        }
    }
    fun releaseInputs() {
        val device = connectedDevice ?: return
        sendReport(device, HidReports.KEYBOARD_ID, HidReports.keyboard(0, 0))
        sendReport(device, HidReports.CONSUMER_ID, HidReports.consumer(0))
        sendReport(device, HidReports.MOUSE_ID, HidReports.mouse(0, 0, 0, 0))
    }
    fun disconnect() { releaseInputs(); connectedDevice?.let { hid?.disconnect(it) } }
    fun close() {
        releaseInputs(); generation++
        val service = hid
        if (service != null) {
            if (registered || registering) service.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, service)
        }
        hid = null; callback = null; connectedDevice = null; connectingDevice = null; cancellingDevice = null
        registered = false; registering = false; requesting = false
    }
}
