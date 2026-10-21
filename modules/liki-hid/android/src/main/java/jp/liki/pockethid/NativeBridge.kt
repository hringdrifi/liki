package jp.liki.pockethid

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import expo.modules.kotlin.Promise
import jp.liki.hid.R

internal class NativeBridge(
    private val context: Context,
    private var activityProvider: () -> Activity?,
    private var emit: (Map<String, Any?>) -> Unit
) {
    private val settings = AppSettings(context)
    private val layouts = LayoutRepository(context)
    private val preferences = context.getSharedPreferences("expo_layout", Context.MODE_PRIVATE)
    init {
        context.deleteSharedPreferences("settings")
        context.deleteSharedPreferences("layout")
    }
    private var message = "準備中…"
    private var controls = false
    private var settingsPage = false
    private var relayRequested = false
    private var pendingPicker: Promise? = null
    private var observedView: View? = null
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGestureExclusion() }
    private val hid = HidController(context, settings) { status, connected ->
        context.mainExecutor.execute { onStatus(status, connected) }
    }
    private val relay = ExternalKeyboardRelay(context, { hid.isConnected() }, hid::keyboardReport, hid::consumerReport,
        hid::relayMouse, { activityProvider()?.window?.decorView }, { publish(); if (relayRequested) studio.retry() },
        { pressed, consumer -> emit(mapOf("relayInput" to mapOf("pressed" to pressed, "consumer" to consumer))) })
    private val studio = StudioTransport(context) { emit(it) }

    private fun onStatus(status: String, connected: Boolean) {
        if (!connected) relay.release()
        relay.updateCapture()
        if (connected != hid.isConnected()) return
        message = status
        LikiSession.connectedAddress = hid.connectedAddress()
        if (connected) {
            hid.connectedAddress()?.let { RecentDevice.remember(preferences, it) }
            LikiSession.reconnectAddress = null
        }
        publish()
    }

    fun invoke(method: String, args: Map<String, Any?>): Any? {
        fun int(key: String) = (args[key] as? Number)?.toInt() ?: 0
        fun string(key: String) = requireNotNull(args[key] as? String)
        return when (method) {
            "initialize" -> { hid.releaseInputs(); relay.enable(false); relayRequested = false; relay.start(); applyWindowSettings(); mapOf("layout" to layouts.initial(), "preferences" to preferences.all,
                "settings" to settingsValues(), "bindings" to KeyBinding.options().map(::bindingMap),
                "density" to context.resources.displayMetrics.density.toDouble(),
                "xdpi" to context.resources.displayMetrics.xdpi.toDouble(),
                "privacy" to context.getString(R.string.privacy_policy_text), "status" to status(), "session" to LikiSession.value) }
            "session" -> { LikiSession.value = string("value"); null }
            "status" -> status()
            "studioRetry" -> { studio.retry(); null }
            "studioWrite" -> { studio.write((args["bytes"] as? List<*>)?.map { (it as Number).toInt() } ?: emptyList()); null }
            "ready" -> { ensureReady(); null }
            "discoverable" -> { discoverable(); null }
            "connect", "cancelConnection" -> error("BLE HIDはPC側のBluetooth設定から接続してください")
            "disconnect" -> { LikiSession.reconnectAddress = null; hid.disconnect(); null }
            "key" -> { hid.key(int("modifiers"), int("code")); null }
            "consumer" -> { hid.consumer(int("usage")); null }
            "move" -> { hid.mouseMove(int("x"), int("y")); null }
            "scroll" -> { hid.scroll(int("amount")); null }
            "zoom" -> { hid.zoom(int("amount")); null }
            "mouseDown" -> { hid.pressMouse(int("button")); null }
            "mouseUp" -> { hid.releaseMouse(int("button")); null }
            "click" -> { hid.clickMouse(int("button")); vibrate(); null }
            "vibrate" -> { vibrate(); null }
            "parse" -> parseLayout(string("json"))
            "binding" -> KeyBinding.named(string("name"))?.let(::bindingMap)
            "asset" -> {
                val name = string("name")
                require(name in listOf("default-kle.json", "ghosted-trackpad.json"))
                context.assets.open(name).bufferedReader().use { it.readText() }
            }
            "preferences" -> {
                val values = requireNotNull(args["values"] as? Map<*, *>)
                val edit = preferences.edit()
                for ((key, value) in values) {
                    require(key is String) { "Unsupported layout preference key" }
                    when (value) {
                    null -> edit.remove(key)
                    is String -> edit.putString(key, value)
                    else -> error("Unsupported layout preference")
                    }
                }
                edit.apply(); null
            }
            "setting" -> { saveSetting(string("key"), args["value"]); applyWindowSettings(); settingsValues() }
            "page" -> {
                controls = args["controls"] == true; settingsPage = args["settings"] == true
                val requested = args["relay"] == true && controls && !settingsPage
                if (relayRequested != requested) { hid.releaseInputs(); relayRequested = requested }
                relay.enable(requested)
                studio.enable(requested)
                applyWindowSettings(); null
            }
            else -> error("Unknown HID command: $method")
        }
    }

    fun pickFile(promise: Promise) {
        check(pendingPicker == null) { "ファイル選択中です" }
        pendingPicker = promise
        requireNotNull(activityProvider()).startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }, 5535)
    }

    private fun ensureReady() {
        if (!hid.hasPermission()) { message = "Bluetooth接続権限が必要です"; publish() }
        else if (!hid.isBluetoothReady()) requireNotNull(activityProvider()).startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 5532)
        else { hid.start(); publish() }
    }
    private fun discoverable() {
        check(Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) { "Bluetooth公開権限が必要です" }
        hid.advertise()
    }
    fun resume() {
        relay.start(); relay.foreground(true)
        if (hid.isBluetoothReady()) { hid.start(); publish() }
        applyWindowSettings()
    }
    fun pause() {
        studio.enable(false)
        relay.foreground(false)
        hid.releaseInputs()
    }
    fun attach(activity: () -> Activity?, events: (Map<String, Any?>) -> Unit) {
        activityProvider = activity; emit = events
        relay.start()
        val focused = activity()?.hasWindowFocus() == true
        relay.foreground(focused); relay.focus(focused)
    }
    fun detach() {
        studio.close()
        pause()
        observedView?.removeOnLayoutChangeListener(layoutListener); observedView = null
        pendingPicker?.reject("liki", "画面が閉じられました", null); pendingPicker = null
        relay.close(); relayRequested = false
        controls = false; settingsPage = false
        activityProvider = { null }; emit = {}
    }
    fun close() {
        detach()
        hid.close()
        LikiSession.connectedAddress = null
    }
    fun activityResult(code: Int, resultCode: Int, data: Intent?) {
        when (code) {
            5532 -> if (resultCode == Activity.RESULT_OK) ensureReady() else message = "Bluetoothをオンにしてください"
            5535 -> {
                val result = pendingPicker; pendingPicker = null
                try {
                    if (resultCode != Activity.RESULT_OK) result?.resolve(null)
                    else {
                        val uri = requireNotNull(data?.data) { "ファイルを開けません" }
                        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                require(output.size() + count <= 1024 * 1024) { "ファイルは1MB以下にしてください" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        result?.resolve(bytes.toString(Charsets.UTF_8))
                    }
                } catch (error: Exception) { result?.reject("liki", error.message, error) }
            }
        }
        publish()
    }

    private fun parseLayout(json: String): Map<String, Any> {
        require(json.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "ファイルは1MB以下にしてください" }
        val parsed = KleLayout.parse(json)
        return mapOf("name" to parsed.name, "keys" to parsed.keys.map { key ->
            mapOf("index" to key.index, "x" to key.x.toDouble(), "y" to key.y.toDouble(),
                "w" to key.w.toDouble(), "h" to key.h.toDouble(), "x2" to key.x2.toDouble(), "y2" to key.y2.toDouble(),
                "w2" to key.w2.toDouble(), "h2" to key.h2.toDouble(), "rx" to key.rx.toDouble(), "ry" to key.ry.toDouble(),
                "rotation" to key.rotation.toDouble(), "color" to key.color, "labels" to key.labels.toList(),
                "decal" to key.decal, "ghost" to key.ghost)
        }, "inferred" to layouts.inferred(json))
    }

    private fun bindingMap(b: KeyBinding): Map<String, Any> = mapOf("name" to b.name, "code" to b.code,
        "modifier" to b.modifier, "layer" to b.layer, "action" to b.layerAction.name, "consumer" to b.consumerUsage)

    private fun status(): Map<String, Any?> = mapOf("message" to message,
        "connected" to hid.isConnected(), "connecting" to false, "cancelling" to false,
        "restoring" to false, "transport" to "ble",
        "relaySupported" to true, "keyboards" to relay.devices(),
        "registered" to hid.isRegistered(), "address" to hid.connectedAddress(),
        "devices" to emptyList<Any>(),
        "lastAddress" to preferences.getString("last_device_address", null))
    private fun publish() { emit(status()) }
    private fun vibrate() { if (settings.touchVibration()) activityProvider()?.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    private fun settingsValues(): Map<String, Any> = mapOf(
        "sticky_modifiers" to settings.stickyModifiers(), "kle_json_key_labels" to settings.kleJsonKeyLabels(),
        "pinch_zoom" to settings.pinchZoom(), "drag_move_keyboard" to settings.dragMoveKeyboard(),
        "keep_screen_on" to settings.keepScreenOn(), "fullscreen_controls" to settings.fullscreenControls(),
        "touch_vibration" to settings.touchVibration(), "screen_orientation" to settings.screenOrientation(),
        "key_pitch_mm" to settings.keyPitchMm().toDouble(), "auto_key_scale_px" to settings.autoKeyScalePx().toDouble(),
        "tap_to_click" to settings.tapToClick(), "hold_to_drag" to settings.holdToDrag(),
        "two_finger_scroll" to settings.twoFingerScroll(), "trackpad_pinch_zoom" to settings.trackpadPinchZoom(),
        "two_finger_right_click" to settings.twoFingerRightClick(), "tap_drag" to settings.tapDrag(),
        "three_finger_middle_click" to settings.threeFingerMiddleClick(), "pointer_speed" to settings.pointerSpeed().toDouble(),
        "pointer_send_mode" to settings.pointerSendMode(), "scroll_speed" to settings.scrollSpeed().toDouble(),
        "reverse_scroll" to settings.reverseScroll(), "border_colors" to (0..3).map(settings::layerBorderColor))

    private fun saveSetting(key: String, value: Any?) {
        when (key) {
            "sticky_modifiers" -> settings.setStickyModifiers(value as Boolean)
            "kle_json_key_labels" -> settings.setKleJsonKeyLabels(value as Boolean)
            "pinch_zoom" -> settings.setPinchZoom(value as Boolean)
            "drag_move_keyboard" -> settings.setDragMoveKeyboard(value as Boolean)
            "keep_screen_on" -> settings.setKeepScreenOn(value as Boolean)
            "fullscreen_controls" -> settings.setFullscreenControls(value as Boolean)
            "touch_vibration" -> settings.setTouchVibration(value as Boolean)
            "screen_orientation" -> settings.setScreenOrientation((value as Number).toInt())
            "key_pitch_mm" -> settings.setKeyPitchMm((value as Number).toFloat())
            "auto_key_scale_px" -> if ((value as Number).toFloat() > 0) settings.setAutoKeyScalePx(value.toFloat()) else settings.clearAutoKeyScalePx()
            "tap_to_click" -> settings.setTapToClick(value as Boolean)
            "hold_to_drag" -> settings.setHoldToDrag(value as Boolean)
            "two_finger_scroll" -> settings.setTwoFingerScroll(value as Boolean)
            "trackpad_pinch_zoom" -> settings.setTrackpadPinchZoom(value as Boolean)
            "two_finger_right_click" -> settings.setTwoFingerRightClick(value as Boolean)
            "tap_drag" -> settings.setTapDrag(value as Boolean)
            "three_finger_middle_click" -> settings.setThreeFingerMiddleClick(value as Boolean)
            "pointer_speed" -> settings.setPointerSpeed((value as Number).toFloat())
            "pointer_send_mode" -> settings.setPointerSendMode((value as Number).toInt())
            "scroll_speed" -> settings.setScrollSpeed((value as Number).toFloat())
            "reverse_scroll" -> settings.setReverseScroll(value as Boolean)
            else -> {
                require(key.matches(Regex("layer_border_color_[0-3]")))
                settings.setLayerBorderColor(key.last().digitToInt(), (value as Number).toInt())
            }
        }
    }

    private fun applyWindowSettings() {
        val activity = activityProvider() ?: return
        val window = activity.window
        if (observedView !== window.decorView) {
            observedView?.removeOnLayoutChangeListener(layoutListener)
            observedView = window.decorView
            window.decorView.addOnLayoutChangeListener(layoutListener)
        }
        updateGestureExclusion()
        activity.requestedOrientation = when (if (controls) settings.screenOrientation() else 0) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            3 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (settings.keepScreenOn()) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val fullscreen = controls && !settingsPage && settings.fullscreenControls()
        if (Build.VERSION.SDK_INT >= 30) window.decorView.windowInsetsController?.apply {
            if (fullscreen) {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            } else show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (fullscreen)
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else 0
        }
    }

    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < 29) return
        val view = activityProvider()?.window?.decorView ?: return
        val density = context.resources.displayMetrics.density
        view.systemGestureExclusionRects = if (controls && !settingsPage)
            listOf(Rect(view.width - (24 * density).toInt(), view.height / 2 - (54 * density).toInt(),
                view.width, view.height / 2 + (54 * density).toInt())) else emptyList()
    }


}
