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

internal class HidController(private val context: Context, private val settings: AppSettings, private val listener: Listener) {
    fun interface Listener { fun onStatus(status: String, connected: Boolean) }

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val hid = BleHidPeripheral(context) { message ->
        if (!isConnected()) { keyHandler.removeCallbacksAndMessages(null); nextKeyTime = 0; mouseButtons = 0; clearMouseMove() }
        status(message)
    }
    private val host: BluetoothDevice? get() = hid.connectedDevice
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
        hid.start()
    }
    fun advertise() {
        check(isBluetoothReady()) { "Bluetoothをオンにし、接続権限を許可してください" }
        hid.advertise()
    }
    fun isRegistered(): Boolean = hid.isReady
    fun disconnect() { releaseInputs(); hid.disconnect() }
    fun isConnected(): Boolean = host != null
    fun connectedAddress(): String? = host?.address

    fun keyboardReport(data: ByteArray) { host?.let { hid.sendReport(it, HidReports.KEYBOARD_ID, data) } }
    fun consumerReport(usage: Int) { host?.let { hid.sendReport(it, HidReports.CONSUMER_ID, HidReports.consumer(usage)) } }

    fun key(modifiers: Int, code: Int) {
        val destination = host ?: return
        val pressAt = maxOf(SystemClock.uptimeMillis(), nextKeyTime)
        val releaseAt = pressAt + 18
        keyHandler.postAtTime({
            if (destination == host) hid.sendReport(destination,
                HidReports.KEYBOARD_ID, HidReports.keyboard(modifiers, code))
        }, pressAt)
        keyHandler.postAtTime({
            if (destination == host) hid.sendReport(destination,
                HidReports.KEYBOARD_ID, HidReports.keyboard(0, 0))
        }, releaseAt)
        nextKeyTime = releaseAt + 12
    }

    fun consumer(usage: Int) {
        val destination = host ?: return
        val pressAt = maxOf(SystemClock.uptimeMillis(), nextKeyTime)
        val releaseAt = pressAt + 18
        keyHandler.postAtTime({
            if (destination == host) hid.sendReport(destination,
                HidReports.CONSUMER_ID, HidReports.consumer(usage))
        }, pressAt)
        keyHandler.postAtTime({
            if (destination == host) hid.sendReport(destination,
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
                hid.sendReport(destination, HidReports.KEYBOARD_ID,
                    HidReports.keyboard(HidReports.MOD_CTRL, 0))
                scroll(amount)
            }
        }, pressAt)
        keyHandler.postAtTime({
            if (destination == host) hid.sendReport(destination,
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
        hid.sendReport(destination, HidReports.MOUSE_ID, data)
    }

    fun releaseInputs() {
        keyHandler.removeCallbacksAndMessages(null); nextKeyTime = 0
        clearMouseMove(); mouseButtons = 0
        hid.releaseInputs()
    }

    fun close() { releaseInputs(); hid.close() }
    private fun clamp(value: Int): Int = value.coerceIn(-127, 127)
    private fun status(value: String) { listener.onStatus(value, isConnected()) }
}
