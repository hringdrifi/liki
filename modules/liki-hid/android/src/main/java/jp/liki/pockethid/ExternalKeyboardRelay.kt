package jp.liki.pockethid

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/** Entry points used by the generated ReactActivity, before React Native consumes KeyEvents. */
object LikiKeyboardRelay {
    internal var current: ExternalKeyboardRelay? = null
    fun dispatchKeyEvent(event: KeyEvent): Boolean = current?.dispatch(event) ?: false
    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = current?.genericMotion(event) ?: false
    fun onWindowFocusChanged(focused: Boolean) { current?.focus(focused) }
}

internal class ExternalKeyboardRelay(
    context: Context,
    private val connected: () -> Boolean,
    private val keyboard: (ByteArray) -> Unit,
    private val consumer: (Int) -> Unit,
    private val mouse: (Int, Int, Int, Int) -> Unit,
    private val mouseSpeed: () -> Float,
    private val windowView: () -> View?,
    private val changed: () -> Unit,
    private val inputChanged: (List<Int>, Int) -> Unit
) : InputManager.InputDeviceListener {
    private val input = context.getSystemService(InputManager::class.java)
    private val state = RelayKeyState()
    private val mouseState = RelayMouseState(mouseSpeed)
    private var pointerView: View? = null
    private var started = false
    private var enabled = false
    private var foreground = true
    private var focused = true
    private var remaps: Map<Int, List<Int>> = emptyMap()
    private var learning = false
    private var useRemaps = true
    private var layout = "none"
    fun configureLayout(value: String) {
        require(value in listOf("none", "us-jis", "jis-us"))
        if (layout != value) { release(); layout = value }
    }
    fun configureRemaps(rules: Map<Int, List<Int>> = remaps, learn: Boolean = learning, apply: Boolean = useRemaps) {
        if (rules != remaps || learn != learning || apply != useRemaps) {
            release(); remaps = rules; learning = learn; useRemaps = apply
        }
    }

    fun start() {
        if (!started) { input.registerInputDeviceListener(this, Handler(Looper.getMainLooper())); started = true }
        LikiKeyboardRelay.current = this
    }
    fun devices(): List<Map<String, Any>> = input.inputDeviceIds.map(input::getInputDevice).filterNotNull().filter(::eligible)
        .map { mapOf("id" to it.id, "name" to it.name) }
    private fun external(device: InputDevice): Boolean = !device.isVirtual &&
        (Build.VERSION.SDK_INT < 29 || device.isExternal)
    private fun keyboardDevice(device: InputDevice): Boolean = external(device) && device.supportsSource(InputDevice.SOURCE_KEYBOARD) &&
        (Build.VERSION.SDK_INT >= 29 || device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC)
    private fun mouseDevice(device: InputDevice): Boolean = external(device) &&
        (device.supportsSource(InputDevice.SOURCE_MOUSE) || device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            device.supportsSource(InputDevice.SOURCE_TOUCHPAD))
    private fun eligible(device: InputDevice): Boolean = keyboardDevice(device) || mouseDevice(device)

    fun enable(value: Boolean) { if (enabled != value) { release(); enabled = value }; updateCapture() }
    fun foreground(value: Boolean) { if (foreground != value) { release(); foreground = value }; updateCapture() }
    fun focus(value: Boolean) { if (focused != value) { release(); focused = value }; updateCapture() }
    fun release() { state.clear(); mouseState.clear(); inputChanged(emptyList(), 0); if (enabled) { keyboard(state.keyboardReport()); consumer(0); mouse(0, 0, 0, 0) } }
    private fun send() {
        val pressed = state.pressedUsages(); val media = state.consumerUsage()
        if (!learning) {
            val report = KeyboardRemapper.translate(pressed, media, if (useRemaps) remaps else emptyMap(), layout)
            keyboard(report.keyboard); consumer(report.consumer)
        }
        inputChanged(pressed, media)
    }
    private fun send(report: RelayMouseState.Report) { mouse(report.buttons, report.x, report.y, report.wheel) }

    fun updateCapture() {
        val root = windowView() as? ViewGroup
        val capture = enabled && foreground && focused && connected() &&
            input.inputDeviceIds.any { input.getInputDevice(it)?.let(::mouseDevice) == true }
        if (!capture || pointerView?.parent !== root) releaseCapture()
        if (!capture || root == null || pointerView != null) return
        val view = object : View(root.context) {
            override fun onPointerCaptureChange(hasCapture: Boolean) {
                super.onPointerCaptureChange(hasCapture)
                if (!hasCapture) mouseState.clear()?.let(::send)
            }
        }.apply {
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha = 0f
            setOnCapturedPointerListener { _, event -> capturedMotion(event) }
        }
        pointerView = view
        root.addView(view, ViewGroup.LayoutParams(1, 1))
        view.requestFocus()
        view.post { if (pointerView === view && enabled && foreground && focused && connected()) view.requestPointerCapture() }
    }

    private fun releaseCapture() {
        val view = pointerView ?: return
        pointerView = null
        view.releasePointerCapture()
        (view.parent as? ViewGroup)?.removeView(view)
        mouseState.clear()?.let(::send)
    }

    fun genericMotion(event: MotionEvent): Boolean {
        if (!enabled || !foreground || !focused || !connected() || event.device?.let(::mouseDevice) != true) return false
        updateCapture()
        // Absolute cursor positions must never be forwarded as relative HID motion.
        return event.actionMasked in listOf(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_SCROLL,
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE)
    }

    private fun capturedMotion(event: MotionEvent): Boolean {
        if (!enabled || !foreground || !focused || !connected() || event.device?.let(::mouseDevice) != true) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                for (index in 0 until event.historySize) mouseState.update(event.deviceId, event.buttonState,
                    event.getHistoricalX(index), event.getHistoricalY(index)).forEach(::send)
                mouseState.update(event.deviceId, event.buttonState, event.x, event.y).forEach(::send)
            }
            MotionEvent.ACTION_SCROLL -> mouseState.update(event.deviceId, event.buttonState,
                wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL)).forEach(::send)
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE ->
                mouseState.update(event.deviceId, event.buttonState).forEach(::send)
            MotionEvent.ACTION_CANCEL -> mouseState.remove(event.deviceId)?.let(::send)
            else -> return false
        }
        return true
    }

    fun dispatch(event: KeyEvent): Boolean {
        if (!enabled || !foreground || !focused || !connected()) return false
        val device = event.device ?: return false
        if (!keyboardDevice(device) || event.action !in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        val usage = keyboardUsage(event.keyCode)
        val media = consumerUsage(event.keyCode)
        if (usage == null && media == null) return false
        if (event.isCanceled) { state.remove(device.id); send(); return true }
        // Holding a key lets the receiving PC generate its own repeat events.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
        val down = event.action == KeyEvent.ACTION_DOWN
        if (usage != null) state.key(device.id, usage, down)
        if (media != null) state.consumer(device.id, event.keyCode, media, down)
        send()
        return true
    }

    override fun onInputDeviceAdded(deviceId: Int) { updateCapture(); changed() }
    override fun onInputDeviceChanged(deviceId: Int) { state.remove(deviceId); if (enabled) { send(); mouseState.remove(deviceId)?.let(::send) }; updateCapture(); changed() }
    override fun onInputDeviceRemoved(deviceId: Int) { onInputDeviceChanged(deviceId) }
    fun close() {
        release(); enabled = false; releaseCapture()
        if (started) { input.unregisterInputDeviceListener(this); started = false }
        if (LikiKeyboardRelay.current === this) LikiKeyboardRelay.current = null
    }

    companion object {
        fun keyboardUsage(code: Int): Int? = when (code) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 4 + code - KeyEvent.KEYCODE_A
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1e + code - KeyEvent.KEYCODE_1
            KeyEvent.KEYCODE_0 -> 0x27
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0x3a + code - KeyEvent.KEYCODE_F1
            in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> 0x59 + code - KeyEvent.KEYCODE_NUMPAD_1
            KeyEvent.KEYCODE_ENTER -> 0x28
            KeyEvent.KEYCODE_ESCAPE -> 0x29
            KeyEvent.KEYCODE_DEL -> 0x2a
            KeyEvent.KEYCODE_TAB -> 0x2b
            KeyEvent.KEYCODE_SPACE -> 0x2c
            KeyEvent.KEYCODE_MINUS -> 0x2d
            KeyEvent.KEYCODE_EQUALS -> 0x2e
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2f
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
            KeyEvent.KEYCODE_BACKSLASH -> 0x31
            KeyEvent.KEYCODE_SEMICOLON -> 0x33
            KeyEvent.KEYCODE_APOSTROPHE -> 0x34
            KeyEvent.KEYCODE_GRAVE -> 0x35
            KeyEvent.KEYCODE_COMMA -> 0x36
            KeyEvent.KEYCODE_PERIOD -> 0x37
            KeyEvent.KEYCODE_SLASH -> 0x38
            KeyEvent.KEYCODE_CAPS_LOCK -> 0x39
            KeyEvent.KEYCODE_SYSRQ -> 0x46
            KeyEvent.KEYCODE_SCROLL_LOCK -> 0x47
            KeyEvent.KEYCODE_BREAK -> 0x48
            KeyEvent.KEYCODE_INSERT -> 0x49
            KeyEvent.KEYCODE_MOVE_HOME -> 0x4a
            KeyEvent.KEYCODE_PAGE_UP -> 0x4b
            KeyEvent.KEYCODE_FORWARD_DEL -> 0x4c
            KeyEvent.KEYCODE_MOVE_END -> 0x4d
            KeyEvent.KEYCODE_PAGE_DOWN -> 0x4e
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4f
            KeyEvent.KEYCODE_DPAD_LEFT -> 0x50
            KeyEvent.KEYCODE_DPAD_DOWN -> 0x51
            KeyEvent.KEYCODE_DPAD_UP -> 0x52
            KeyEvent.KEYCODE_NUM_LOCK -> 0x53
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x54
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x55
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x56
            KeyEvent.KEYCODE_NUMPAD_ADD -> 0x57
            KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x58
            KeyEvent.KEYCODE_NUMPAD_0 -> 0x62
            KeyEvent.KEYCODE_NUMPAD_DOT -> 0x63
            KeyEvent.KEYCODE_NUMPAD_EQUALS -> 0x67
            KeyEvent.KEYCODE_MENU -> 0x65
            KeyEvent.KEYCODE_RO -> 0x87
            KeyEvent.KEYCODE_KATAKANA_HIRAGANA -> 0x88
            KeyEvent.KEYCODE_YEN -> 0x89
            KeyEvent.KEYCODE_HENKAN -> 0x8a
            KeyEvent.KEYCODE_MUHENKAN -> 0x8b
            KeyEvent.KEYCODE_LANGUAGE_SWITCH -> 0x90
            KeyEvent.KEYCODE_EISU -> 0x91
            KeyEvent.KEYCODE_CTRL_LEFT -> 0xe0
            KeyEvent.KEYCODE_SHIFT_LEFT -> 0xe1
            KeyEvent.KEYCODE_ALT_LEFT -> 0xe2
            KeyEvent.KEYCODE_META_LEFT -> 0xe3
            KeyEvent.KEYCODE_CTRL_RIGHT -> 0xe4
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xe5
            KeyEvent.KEYCODE_ALT_RIGHT -> 0xe6
            KeyEvent.KEYCODE_META_RIGHT -> 0xe7
            else -> null
        }

        fun consumerUsage(code: Int): Int? = when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> 0xcd
            KeyEvent.KEYCODE_MEDIA_PLAY -> 0xb0
            KeyEvent.KEYCODE_MEDIA_PAUSE -> 0xb1
            KeyEvent.KEYCODE_MEDIA_NEXT -> 0xb5
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> 0xb6
            KeyEvent.KEYCODE_MEDIA_STOP -> 0xb7
            KeyEvent.KEYCODE_VOLUME_MUTE -> 0xe2
            KeyEvent.KEYCODE_VOLUME_UP -> 0xe9
            KeyEvent.KEYCODE_VOLUME_DOWN -> 0xea
            else -> null
        }
    }
}
