package jp.liki.pockethid

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal class HidController(private val context: Context, private val settings: AppSettings, private val listener: Listener) {
    fun interface Listener { fun onStatus(status: String, connected: Boolean) }

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var connectingDevice: BluetoothDevice? = null
    private var cancellingDevice: BluetoothDevice? = null
    private var restoringAfterCancel = false
    private var registered = false
    private var registering = false
    private var bootMode = false
    private var mouseButtons = 0
    private val physicalKeys = mutableMapOf<Int, Pair<Int, Int>>()
    private val physicalMouseButtons = mutableMapOf<Int, Int>()
    private var tapModifiers = 0
    private var tapCode = 0
    private val keyHandler = Handler(Looper.getMainLooper())
    private var nextKeyTime = 0L
    private val mouseHandler = Handler(Looper.getMainLooper())
    private var pendingMouseX = 0
    private var pendingMouseY = 0
    private var nextMouseMoveTime = 0L
    private var mouseMoveScheduled = false
    private val sendPendingMouseMove = Runnable { flushMouseMove() }

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothReady(): Boolean = adapter != null && hasPermission() && adapter.isEnabled

    fun pairedDevices(): List<BluetoothDevice> =
        if (isBluetoothReady()) adapter!!.bondedDevices.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" })
        else emptyList()

    fun start() {
        val bluetooth = adapter
        if (bluetooth == null) { status("この端末はBluetoothに対応していません"); return }
        if (!hasPermission()) { status("Bluetoothの許可が必要です"); return }
        if (!bluetooth.isEnabled) { status("Bluetoothをオンにしてください"); return }
        if (hid != null) { register(); return }
        status("HIDサービスを準備中…")
        if (!bluetooth.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE))
            status("HIDサービスを利用できません。この端末がHID Deviceに対応しているか確認してください")
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            hid = proxy as BluetoothHidDevice
            register()
        }
        override fun onServiceDisconnected(profile: Int) {
            hid = null; host = null; connectingDevice = null; cancellingDevice = null
            restoringAfterCancel = false; registered = false; registering = false
            clearMouseMove()
            clearPhysicalState()
            status("HIDサービスが切断されました")
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            registering = false
            if (restoringAfterCancel) {
                if (!isRegistered) {
                    register()
                    if (!registering) { restoringAfterCancel = false; cancellingDevice = null }
                } else {
                    restoringAfterCancel = false; cancellingDevice = null
                    status("接続をキャンセルしました")
                }
                return
            }
            if (!registered) {
                host = null; connectingDevice = null
                status("HID登録が解除されました。画面を開き直して再試行してください")
            } else status("準備完了。PCとペアリングして接続してください")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (cancellingDevice == device) {
                if (state == BluetoothProfile.STATE_CONNECTED) hid?.disconnect(device)
                return
            }
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectingDevice = null; host = device; bootMode = false; mouseButtons = 0
                    clearMouseMove()
                    clearPhysicalState()
                    status("接続中: ${deviceName(device)}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val failedAttempt = connectingDevice == device
                    if (failedAttempt) connectingDevice = null
                    keyHandler.removeCallbacksAndMessages(null)
                    nextKeyTime = 0
                    if (host == device) host = null
                    mouseButtons = 0
                    clearPhysicalState()
                    clearMouseMove()
                    status(if (failedAttempt) "接続できませんでした。再試行できます"
                        else "切断されました。接続を押して再試行できます")
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    connectingDevice = device
                    status("接続中…")
                }
            }
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            bootMode = protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val service = hid ?: return
            val data = when {
                type == BluetoothHidDevice.REPORT_TYPE_INPUT &&
                    (id.toInt() == HidReports.KEYBOARD_ID || (bootMode && id.toInt() == 0)) -> keyboardReport()
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && id.toInt() == HidReports.MOUSE_ID ->
                    HidReports.mouse(allMouseButtons(), 0, 0, 0)
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && id.toInt() == HidReports.CONSUMER_ID ->
                    HidReports.consumer(0)
                type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id.toInt() == HidReports.KEYBOARD_ID -> byteArrayOf(0)
                else -> { service.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM); return }
            }
            service.replyReport(device, type, id, data)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            host = null; mouseButtons = 0; clearPhysicalState()
            status("PC側でペアリングが解除されました")
        }
    }

    private fun register() {
        val service = hid ?: return
        if (registered || registering) return
        val sdp = BluetoothHidDeviceAppSdpSettings("Pocket HID", "Keyboard and trackpad", "Pocket HID",
            BluetoothHidDevice.SUBCLASS1_COMBO, HidReports.DESCRIPTOR)
        registering = service.registerApp(sdp, null, null, context.mainExecutor, callback)
        if (!registering) status("HID登録を開始できませんでした")
    }

    fun isRegistered(): Boolean = registered

    fun connect(device: BluetoothDevice?) {
        val service = hid
        if (!registered || service == null) { status("HIDの準備が完了していません"); return }
        if (device == null) { status("ペアリング済みPCを選択してください"); return }
        if (connectingDevice != null || cancellingDevice != null) return
        connectingDevice = device
        if (service.connect(device)) status("接続中…")
        else { connectingDevice = null; status("接続を開始できませんでした。PC側のBluetooth設定を確認してください") }
    }

    fun cancelConnection() {
        val device = connectingDevice ?: return
        val service = hid ?: return
        if (!service.unregisterApp()) {
            status("接続をキャンセルできませんでした。もう一度お試しください")
            return
        }
        connectingDevice = null; cancellingDevice = device
        restoringAfterCancel = true; registered = false
        status("接続をキャンセル中…")
    }

    fun disconnect() { host?.let { hid?.disconnect(it) } }
    fun isConnected(): Boolean = hid != null && host != null
    fun isConnecting(): Boolean = connectingDevice != null
    fun isCancelling(): Boolean = cancellingDevice != null
    fun connectedAddress(): String? = host?.address

    fun key(modifiers: Int, code: Int) {
        val destination = host ?: return
        if (hid == null) return
        val pressAt = maxOf(SystemClock.uptimeMillis(), nextKeyTime)
        val releaseAt = pressAt + 18
        keyHandler.postAtTime({
            if (destination == host) {
                tapModifiers = modifiers; tapCode = code
                sendKeyboard()
            }
        }, pressAt)
        keyHandler.postAtTime({
            if (destination == host) {
                tapModifiers = 0; tapCode = 0
                sendKeyboard()
            }
        }, releaseAt)
        nextKeyTime = releaseAt + 12
    }

    fun physicalKey(position: Int, modifiers: Int, code: Int, pressed: Boolean) {
        if (pressed) physicalKeys[position] = modifiers to code else physicalKeys.remove(position)
        sendKeyboard()
    }

    private fun keyboardReport(): ByteArray {
        val modifiers = physicalKeys.values.fold(tapModifiers) { acc, key -> acc or key.first }
        val keys = physicalKeys.values.map { it.second } + tapCode
        return HidReports.keyboardState(modifiers, keys)
    }

    private fun sendKeyboard() {
        val destination = host ?: return
        hid?.sendReport(destination, if (bootMode) 0 else HidReports.KEYBOARD_ID, keyboardReport())
    }

    private fun clearPhysicalState() {
        physicalKeys.clear(); physicalMouseButtons.clear(); tapModifiers = 0; tapCode = 0
    }

    fun consumer(usage: Int) {
        val destination = host ?: return
        if (hid == null || bootMode) return
        val pressAt = maxOf(SystemClock.uptimeMillis(), nextKeyTime)
        val releaseAt = pressAt + 18
        keyHandler.postAtTime({
            if (destination == host && !bootMode) hid?.sendReport(destination,
                HidReports.CONSUMER_ID, HidReports.consumer(usage))
        }, pressAt)
        keyHandler.postAtTime({
            if (destination == host && !bootMode) hid?.sendReport(destination,
                HidReports.CONSUMER_ID, HidReports.consumer(0))
        }, releaseAt)
        nextKeyTime = releaseAt + 12
    }

    fun text(value: String): Boolean {
        if (value.any { HidReports.ascii(it) == null }) return false
        value.forEach { char ->
            val code = HidReports.ascii(char)!!
            key(code[0], code[1])
        }
        return true
    }

    fun mouseMove(x: Int, y: Int) {
        pendingMouseX += x
        pendingMouseY += y
        if (pendingMouseX == 0 && pendingMouseY == 0) return
        val interval = when (settings.pointerSendMode()) {
            0 -> 0L
            2 -> 32L
            else -> 16L
        }
        val now = SystemClock.uptimeMillis()
        if (interval == 0L || now >= nextMouseMoveTime) flushMouseMove(interval)
        else if (!mouseMoveScheduled) {
            mouseMoveScheduled = true
            mouseHandler.postAtTime(sendPendingMouseMove, nextMouseMoveTime)
        }
    }

    private fun flushMouseMove(interval: Long = when (settings.pointerSendMode()) {
        0 -> 0L
        2 -> 32L
        else -> 16L
    }) {
        mouseHandler.removeCallbacks(sendPendingMouseMove)
        mouseMoveScheduled = false
        var remainingX = pendingMouseX; var remainingY = pendingMouseY
        pendingMouseX = 0; pendingMouseY = 0
        if (remainingX == 0 && remainingY == 0) return
        while (remainingX != 0 || remainingY != 0) {
            val dx = clamp(remainingX); val dy = clamp(remainingY)
            sendMouse(dx, dy, 0)
            remainingX -= dx; remainingY -= dy
        }
        nextMouseMoveTime = SystemClock.uptimeMillis() + interval
    }

    private fun clearMouseMove() {
        mouseHandler.removeCallbacks(sendPendingMouseMove)
        mouseMoveScheduled = false
        pendingMouseX = 0; pendingMouseY = 0
        nextMouseMoveTime = 0L
    }

    fun scroll(amount: Int) {
        flushMouseMove()
        var remaining = amount
        while (remaining != 0) {
            val step = clamp(remaining)
            sendMouse(0, 0, step)
            remaining -= step
        }
    }

    fun zoom(amount: Int) {
        val destination = host ?: return
        if (hid == null || amount == 0) return
        flushMouseMove()
        val pressAt = maxOf(SystemClock.uptimeMillis(), nextKeyTime)
        val releaseAt = pressAt + 24
        keyHandler.postAtTime({
            if (destination == host) {
                hid?.sendReport(destination, if (bootMode) 0 else HidReports.KEYBOARD_ID,
                    HidReports.keyboardState(HidReports.MOD_CTRL or
                        physicalKeys.values.fold(0) { acc, key -> acc or key.first },
                        physicalKeys.values.map { it.second }))
                scroll(amount)
            }
        }, pressAt)
        keyHandler.postAtTime({
            if (destination == host) sendKeyboard()
        }, releaseAt)
        nextKeyTime = releaseAt + 12
    }

    fun pressMouse(button: Int) { flushMouseMove(); mouseButtons = mouseButtons or button; sendMouse(0, 0, 0) }
    fun releaseMouse(button: Int) { flushMouseMove(); mouseButtons = mouseButtons and button.inv(); sendMouse(0, 0, 0) }
    fun clickMouse(button: Int) { pressMouse(button); releaseMouse(button) }

    fun physicalMouseButton(position: Int, button: Int, pressed: Boolean) {
        flushMouseMove()
        if (pressed) physicalMouseButtons[position] = button else physicalMouseButtons.remove(position)
        sendMouse(0, 0, 0)
    }

    private fun allMouseButtons(): Int = physicalMouseButtons.values.fold(mouseButtons) { acc, button -> acc or button }

    private fun sendMouse(x: Int, y: Int, wheel: Int) {
        val destination = host ?: return
        val service = hid ?: return
        val data = HidReports.mouse(allMouseButtons(), x, y, wheel)
        if (bootMode) service.sendReport(destination, 0, byteArrayOf(data[0], data[1], data[2]))
        else service.sendReport(destination, HidReports.MOUSE_ID, data)
    }

    fun close() {
        keyHandler.removeCallbacksAndMessages(null)
        clearPhysicalState()
        clearMouseMove()
        val service = hid
        if (service != null) {
            host?.let { destination ->
                service.sendReport(destination, if (bootMode) 0 else HidReports.KEYBOARD_ID, HidReports.keyboard(0, 0))
                if (!bootMode) service.sendReport(destination, HidReports.CONSUMER_ID, HidReports.consumer(0))
                mouseButtons = 0
                sendMouse(0, 0, 0)
            }
            if (registered) service.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, service)
            hid = null
        }
        host = null; connectingDevice = null; cancellingDevice = null
        restoringAfterCancel = false; registered = false; registering = false
    }

    private fun clamp(value: Int): Int = value.coerceIn(-127, 127)
    private fun deviceName(device: BluetoothDevice): String = device.name?.takeIf { it.isNotEmpty() } ?: device.address
    private fun status(value: String) { listener.onStatus(value, isConnected()) }
}
