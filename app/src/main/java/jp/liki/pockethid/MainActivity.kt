package jp.liki.pockethid

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/** Android HID, permissions, document access and the existing preference formats. */
class MainActivity : FlutterActivity() {
    private lateinit var hid: HidController
    private lateinit var settings: AppSettings
    private lateinit var layouts: LayoutRepository
    private var channel: MethodChannel? = null
    private var message = "準備中…"
    private var visible = false
    private var controls = false
    private var settingsPage = false
    private var reconnectAddress: String? = null
    private var reconnectAttempted = false
    private var pendingPicker: MethodChannel.Result? = null
    private var session: String? = null
    private val preferences by lazy { getSharedPreferences("layout", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)
        layouts = LayoutRepository(this)
        reconnectAddress = savedInstanceState?.getString("reconnect_address")
        session = savedInstanceState?.getString("flutter_session")
        hid = HidController(this, settings) { status, connected -> runOnUiThread {
            if (connected != hid.isConnected()) return@runOnUiThread
            message = status
            if (connected) {
                hid.connectedAddress()?.let { RecentDevice.remember(preferences, it) }
                reconnectAddress = null; reconnectAttempted = false
            }
            maybeReconnect(); publish()
        } }
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 29) window.decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGestureExclusion() }
        applyWindowSettings()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "jp.liki/hid").also {
            it.setMethodCallHandler { call, result ->
                try {
                    fun int(key: String) = call.argument<Number>(key)?.toInt() ?: 0
                    fun string(key: String) = requireNotNull(call.argument<String>(key))
                    val response: Any? = when (call.method) {
                        "initialize" -> mapOf("layout" to layouts.initial(), "preferences" to preferences.all,
                            "settings" to settingsValues(), "bindings" to KeyBinding.options().map(::bindingMap),
                            "density" to resources.displayMetrics.density.toDouble(),
                            "xdpi" to resources.displayMetrics.xdpi.toDouble(),
                            "privacy" to getString(R.string.privacy_policy_text), "status" to status(), "session" to session)
                        "session" -> { session = string("value"); null }
                        "status" -> status()
                        "ready" -> { ensureReady(); null }
                        "discoverable" -> { discoverable(); null }
                        "connect" -> { hid.connect(hid.pairedDevices().firstOrNull { it.address == string("address") }); null }
                        "cancelConnection" -> { hid.cancelConnection(); null }
                        "disconnect" -> { reconnectAddress = null; reconnectAttempted = true; hid.disconnect(); null }
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
                            assets.open(name).bufferedReader().use { input -> input.readText() }
                        }
                        "pickFile" -> {
                            check(pendingPicker == null) { "ファイル選択中です" }
                            pendingPicker = result
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }, 5)
                            return@setMethodCallHandler
                        }
                        "preferences" -> {
                            val values = requireNotNull(call.argument<Map<String, Any?>>("values"))
                            val edit = preferences.edit()
                            for ((key, value) in values) when (value) {
                                null -> edit.remove(key)
                                is String -> edit.putString(key, value)
                                else -> error("Unsupported layout preference")
                            }
                            edit.apply(); null
                        }
                        "setting" -> {
                            saveSetting(string("key"), call.argument<Any>("value"))
                            applyWindowSettings(); settingsValues()
                        }
                        "page" -> {
                            controls = call.argument<Boolean>("controls") == true
                            settingsPage = call.argument<Boolean>("settings") == true
                            applyWindowSettings(); null
                        }
                        else -> { result.notImplemented(); return@setMethodCallHandler }
                    }
                    result.success(response)
                } catch (error: Exception) { result.error("liki", error.message, null) }
            }
        }
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
        "connected" to hid.isConnected(), "connecting" to hid.isConnecting(), "cancelling" to hid.isCancelling(),
        "restoring" to (reconnectAddress != null && !hid.isConnected() && (!visible || !reconnectAttempted || hid.isConnecting())),
        "registered" to hid.isRegistered(), "address" to hid.connectedAddress(),
        "devices" to hid.pairedDevices().map { mapOf("address" to it.address, "name" to (it.name?.takeIf(String::isNotEmpty) ?: it.address)) },
        "lastAddress" to preferences.getString("last_device_address", null))
    private fun publish() { channel?.invokeMethod("statusChanged", status()) }
    private fun vibrate() { if (settings.touchVibration()) window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

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

    private fun ensureReady() {
        if (Build.VERSION.SDK_INT >= 31 && !hid.hasPermission()) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        else if (!hid.isBluetoothReady()) startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2)
        else { hid.start(); publish() }
    }
    private fun discoverable() {
        check(hid.isRegistered()) { "HIDの準備が完了してから再試行してください" }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE), 3)
        else startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }, 4)
    }
    private fun maybeReconnect() {
        val address = reconnectAddress ?: return
        if (!visible || reconnectAttempted || !hid.isRegistered() || hid.isConnected() || hid.isConnecting() || hid.isCancelling()) return
        reconnectAttempted = true
        hid.pairedDevices().firstOrNull { it.address == address }?.let(hid::connect)
    }
    override fun onResume() {
        super.onResume(); visible = true
        if (::hid.isInitialized && hid.isBluetoothReady()) { hid.start(); maybeReconnect(); publish() }
        if (::settings.isInitialized) applyWindowSettings()
    }
    override fun onPause() {
        if (::hid.isInitialized) reconnectAddress = hid.connectedAddress() ?: reconnectAddress
        visible = false; reconnectAttempted = false
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("reconnect_address", reconnectAddress ?: hid.connectedAddress())
        outState.putString("flutter_session", session)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        pendingPicker?.error("liki", "画面が閉じられました", null); pendingPicker = null
        if (::hid.isInitialized) hid.close()
        channel?.setMethodCallHandler(null); channel = null
        super.onDestroy()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        val granted = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            1 -> if (granted) ensureReady() else message = "Bluetooth接続権限が必要です"
            3 -> if (granted) discoverable() else message = "Bluetooth公開権限が必要です"
        }
        publish()
    }
    @Deprecated("Android document and Bluetooth activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            2 -> if (resultCode == Activity.RESULT_OK) ensureReady() else message = "Bluetoothをオンにしてください"
            4 -> if (resultCode > 0) message = "公開中です。PCからペアリングしてください"
            5 -> {
                val result = pendingPicker; pendingPicker = null
                try {
                    if (resultCode != Activity.RESULT_OK) result?.success(null)
                    else {
                        val uri = requireNotNull(data?.data) { "ファイルを開けません" }
                        val bytes = requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 1024 * 1024) { "ファイルは1MB以下にしてください" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        result?.success(bytes.toString(Charsets.UTF_8))
                    }
                } catch (error: Exception) { result?.error("liki", error.message, null) }
            }
        }
        publish()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::settings.isInitialized) applyWindowSettings()
    }
    private fun applyWindowSettings() {
        updateGestureExclusion()
        requestedOrientation = when (if (controls) settings.screenOrientation() else 0) {
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
        val view = window.decorView
        val density = resources.displayMetrics.density
        view.systemGestureExclusionRects = if (controls && !settingsPage)
            listOf(Rect(view.width - (24 * density).toInt(), view.height / 2 - (54 * density).toInt(),
                view.width, view.height / 2 + (54 * density).toInt())) else emptyList()
    }
}
