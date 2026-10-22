package jp.liki.pockethid

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal class HidController(private val context: Context, private val settings: AppSettings,
    initialTransport: String = "ble", private val listener: Listener) {
    fun interface Listener { fun onStatus(status: String, connected: Boolean) }

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    var transport = if (initialTransport == "classic") "classic" else "ble"
        private set
    private val hid = BleHidPeripheral(context) { message -> if (transport == "ble") transportStatus(message) }
    private val classic = ClassicHidPeripheral(context) { message -> if (transport == "classic") transportStatus(message) }
    private fun transportStatus(message: String) {
        if (!isConnected()) { keyHandler.removeCallbacksAndMessages(null); nextKeyTime = 0; mouseButtons = 0; clearMouseMove() }
        status(message)
    }
    private val host: BluetoothDevice? get() = if (transport == "classic") classic.connectedDevice else hid.connectedDevice
    private var mouseButtons = 0
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

    fun start() {
        if (!hasPermission()) { status("Bluetoothの許可が必要です"); return }
        if (transport == "classic") classic.start() else hid.start()
    }
    fun advertise() {
        check(isBluetoothReady()) { "Bluetoothをオンにし、接続権限を許可してください" }
        hid.advertise()
    }
    fun isRegistered(): Boolean = if (transport == "classic") classic.isReady else hid.isReady
    fun isConnecting(): Boolean = transport == "classic" && classic.isConnecting
    fun isCancelling(): Boolean = transport == "classic" && classic.isCancelling
    fun pairedDevices(): List<BluetoothDevice> = if (transport == "classic" && isBluetoothReady()) classic.pairedDevices() else emptyList()
    fun connect(address: String) { check(transport == "classic"); classic.connect(address) }
    fun cancelConnection() { if (transport == "classic") classic.cancelConnection() }
    fun selectTransport(value: String) {
        require(value in listOf("ble", "classic"))
        check(!isConnected() && !isConnecting() && !isCancelling()) { "切断してから接続方式を変更してください" }
        if (transport == value) return
        releaseInputs()
        if (transport == "classic") classic.close() else hid.close()
        transport = value
        if (isBluetoothReady()) start() else status("Bluetoothをオンにしてください")
    }
    fun disconnect() { releaseInputs(); if (transport == "classic") classic.disconnect() else hid.disconnect() }
    fun isConnected(): Boolean = host != null
    fun connectedAddress(): String? = host?.address

    fun keyboardReport(data: ByteArray) { host?.let { sendReport(it, HidReports.KEYBOARD_ID, data) } }
    fun consumerReport(usage: Int) { host?.let { sendReport(it, HidReports.CONSUMER_ID, HidReports.consumer(usage)) } }

    fun key(modifiers: Int, code: Int) {
        val destination = host ?: return
        val pressAt = maxOf(SystemClock.uptimeMillis(), nextKeyTime)
        val releaseAt = pressAt + 18
        keyHandler.postAtTime({
            if (destination == host) sendReport(destination,
                HidReports.KEYBOARD_ID, HidReports.keyboard(modifiers, code))
        }, pressAt)
        keyHandler.postAtTime({
            if (destination == host) sendReport(destination,
                HidReports.KEYBOARD_ID, HidReports.keyboard(0, 0))
        }, releaseAt)
        nextKeyTime = releaseAt + 12
    }

    fun consumer(usage: Int) {
        val destination = host ?: return
        val pressAt = maxOf(SystemClock.uptimeMillis(), nextKeyTime)
        val releaseAt = pressAt + 18
        keyHandler.postAtTime({
            if (destination == host) sendReport(destination,
                HidReports.CONSUMER_ID, HidReports.consumer(usage))
        }, pressAt)
        keyHandler.postAtTime({
            if (destination == host) sendReport(destination,
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
        if (amount == 0) return
        flushMouseMove()
        val pressAt = maxOf(SystemClock.uptimeMillis(), nextKeyTime)
        val releaseAt = pressAt + 24
        keyHandler.postAtTime({
            if (destination == host) {
                sendReport(destination, HidReports.KEYBOARD_ID,
                    HidReports.keyboard(HidReports.MOD_CTRL, 0))
                scroll(amount)
            }
        }, pressAt)
        keyHandler.postAtTime({
            if (destination == host) sendReport(destination,
                HidReports.KEYBOARD_ID, HidReports.keyboard(0, 0))
        }, releaseAt)
        nextKeyTime = releaseAt + 12
    }

    fun pressMouse(button: Int) { flushMouseMove(); mouseButtons = mouseButtons or button; sendMouse(0, 0, 0) }
    fun releaseMouse(button: Int) { flushMouseMove(); mouseButtons = mouseButtons and button.inv(); sendMouse(0, 0, 0) }
    fun clickMouse(button: Int) { pressMouse(button); releaseMouse(button) }

    fun relayMouse(buttons: Int, x: Int, y: Int, wheel: Int) {
        if (mouseButtons != buttons) { flushMouseMove(); mouseButtons = buttons and 7; sendMouse(0, 0, 0) }
        mouseMove(x, y)
        if (wheel != 0) scroll(wheel)
    }

    private fun sendMouse(x: Int, y: Int, wheel: Int) {
        val destination = host ?: return
        val data = HidReports.mouse(mouseButtons, x, y, wheel)
        sendReport(destination, HidReports.MOUSE_ID, data)
    }

    fun releaseInputs() {
        keyHandler.removeCallbacksAndMessages(null); nextKeyTime = 0
        clearMouseMove(); mouseButtons = 0
        if (transport == "classic") classic.releaseInputs() else hid.releaseInputs()
    }

    private fun sendReport(device: BluetoothDevice, id: Int, data: ByteArray) {
        if (transport == "classic") classic.sendReport(device, id, data) else hid.sendReport(device, id, data)
    }
    fun close() { releaseInputs(); hid.close(); classic.close() }
    private fun clamp(value: Int): Int = value.coerceIn(-127, 127)
    private fun status(value: String) { listener.onStatus(value, isConnected()) }
}
