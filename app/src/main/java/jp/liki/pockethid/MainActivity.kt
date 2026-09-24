package jp.liki.pockethid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.text.InputType
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private companion object {
        const val REQUEST_CONNECT = 1
        const val REQUEST_ENABLE = 2
        const val REQUEST_ADVERTISE = 3
        const val REQUEST_DISCOVERABLE = 4
        const val REQUEST_KLE = 5
    }

    private lateinit var hid: HidController
    private lateinit var settings: AppSettings
    private lateinit var preferences: SharedPreferences
    private lateinit var savedLayouts: SavedLayoutStore
    private var statusView: TextView? = null
    private var devices: Spinner? = null
    private var connectionButton: Button? = null
    private var menuHandle: View? = null
    private var menuScrim: View? = null
    private var menuPanel: LinearLayout? = null
    private val paired = mutableListOf<BluetoothDevice>()
    private var showingControls = false
    private var settingsVisible = false
    private var activityVisible = false
    private var reconnectAddress: String? = null
    private var reconnectAttempted = false
    private var statusText = "準備中…"
    private lateinit var layout: KleLayout
    private lateinit var currentKleJson: String
    private var selectedSavedLayoutName: String? = null
    private var overrides = JSONObject()
    private var layerOverrides = JSONObject()
    private var activeLayer = 0
    private var activeLayerKeyIndex: Int? = null
    private var oneShotReturnLayer: Int? = null
    private var oneShotReturnKeyIndex: Int? = null
    private var momentaryPreviousLayer: Int? = null
    private var momentaryPreviousKeyIndex: Int? = null
    private var momentaryTargetLayer: Int? = null
    private var layerIndicator: TextView? = null
    private var bindingEditMode = false
    private var bindingEditLayer = 0
    private var bindingEditBaseSnapshot: String? = null
    private var bindingEditLayersSnapshot: String? = null
    private var bindingLayerButtons = emptyList<Button>()
    private var keyboardView: KleKeyboardView? = null
    private var keyboardViewState: KleKeyboardView.ViewState? = null
    private var modifiers = 0
    private var backCallback: OnBackInvokedCallback? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        preferences = getSharedPreferences("layout", MODE_PRIVATE)
        savedLayouts = SavedLayoutStore(preferences)
        settings = AppSettings(this)
        applyKeepScreenOn()
        reconnectAddress = state?.getString("reconnect_address")
        activeLayer = state?.getInt("active_layer", 0)?.coerceIn(0, 2) ?: 0
        activeLayerKeyIndex = state?.getInt("active_layer_key", -1)?.takeIf { it >= 0 }
        oneShotReturnLayer = state?.getInt("one_shot_return_layer", -1)?.takeIf { it in 0..2 }
        oneShotReturnKeyIndex = state?.getInt("one_shot_return_key", -1)?.takeIf { it >= 0 }
        if (state?.containsKey("keyboard_zoom") == true) {
            keyboardViewState = KleKeyboardView.ViewState(
                state.getFloat("keyboard_zoom"), state.getFloat("keyboard_pan_x"), state.getFloat("keyboard_pan_y"))
        }
        loadLayout()
        if (state?.getBoolean("binding_edit_mode") == true) {
            bindingEditMode = true
            bindingEditLayer = state.getInt("binding_edit_layer", 0).coerceIn(0, 2)
            bindingEditBaseSnapshot = state.getString("binding_edit_base_snapshot") ?: overrides.toString()
            bindingEditLayersSnapshot = state.getString("binding_edit_layers_snapshot") ?: layerOverrides.toString()
            try {
                overrides = JSONObject(state.getString("binding_edit_base_draft") ?: overrides.toString())
                layerOverrides = JSONObject(state.getString("binding_edit_layers_draft") ?: layerOverrides.toString())
            } catch (_: Exception) { cancelBindingEditDraft() }
        }
        hid = HidController(this, settings) { message, connected ->
            runOnUiThread {
                if (connected != hid.isConnected()) return@runOnUiThread
                val address = if (connected) hid.connectedAddress() else null
                if (address != null) RecentDevice.remember(preferences, address)
                if (connected) { reconnectAddress = null; reconnectAttempted = false }
                statusText = message
                maybeReconnect()
                if (connected != showingControls && !restoringConnection()) showPage(connected)
                else updateStatus()
            }
        }
        showPage(false)
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback {
                if (!handleBack()) finish()
            }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        ensureReady()
    }

    override fun onResume() {
        super.onResume()
        activityVisible = true
        if (::hid.isInitialized && hid.isBluetoothReady()) {
            hid.start()
            if (hid.isConnected()) { reconnectAddress = null; reconnectAttempted = false }
            maybeReconnect()
            if (hid.isConnected() != showingControls && !restoringConnection()) showPage(hid.isConnected())
            else updateStatus()
            refreshDevices()
        }
    }

    override fun onPause() {
        if (::hid.isInitialized) reconnectAddress = hid.connectedAddress() ?: reconnectAddress
        reconnectAttempted = false
        activityVisible = false
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("reconnect_address", reconnectAddress ?: hid.connectedAddress())
        outState.putInt("active_layer", momentaryPreviousLayer ?: activeLayer)
        outState.putInt("active_layer_key",
            (if (momentaryPreviousLayer != null) momentaryPreviousKeyIndex else activeLayerKeyIndex) ?: -1)
        outState.putInt("one_shot_return_layer", oneShotReturnLayer ?: -1)
        outState.putInt("one_shot_return_key", oneShotReturnKeyIndex ?: -1)
        outState.putBoolean("binding_edit_mode", bindingEditMode)
        if (bindingEditMode) {
            outState.putInt("binding_edit_layer", bindingEditLayer)
            outState.putString("binding_edit_base_snapshot", bindingEditBaseSnapshot)
            outState.putString("binding_edit_layers_snapshot", bindingEditLayersSnapshot)
            outState.putString("binding_edit_base_draft", overrides.toString())
            outState.putString("binding_edit_layers_draft", layerOverrides.toString())
        }
        (keyboardView?.viewState() ?: keyboardViewState)?.let {
            outState.putFloat("keyboard_zoom", it.zoom)
            outState.putFloat("keyboard_pan_x", it.panX)
            outState.putFloat("keyboard_pan_y", it.panY)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
        }
        if (::hid.isInitialized) hid.close()
        super.onDestroy()
    }

    private fun showPage(connected: Boolean, preserveKeyboardViewState: Boolean = true) {
        if (preserveKeyboardViewState) keyboardView?.let { keyboardViewState = it.viewState() }
        showingControls = connected
        settingsVisible = false
        applyScreenOrientation()
        devices = null; connectionButton = null; keyboardView = null; layerIndicator = null
        bindingLayerButtons = emptyList()
        menuHandle = null; menuScrim = null; menuPanel = null
        if (connected) showControls() else showConnection()
        updateStatus()
    }

    private fun showConnection() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(AppColors.BACKGROUND)
        }
        val root = column().apply { setPadding(dp(20), dp(24), dp(20), dp(24)) }
        scroll.addView(root)
        setInsetContentView(scroll)
        root.addView(label("Liki", 36, true))
        root.addView(label("Pocket HID", 15, true).apply { setTextColor(AppColors.ACCENT) })
        root.addView(label("スマホをPCのキーボード・トラックパッドに", 14, false))
        statusView = label(statusText, 15, true).also {
            it.setPadding(0, dp(26), 0, dp(24))
            root.addView(it)
        }
        root.addView(section("1. PCとペアリング"))
        root.addView(label("下のボタンを押した後、PCのBluetooth設定からこのスマホを追加します。", 14, false))
        root.addView(button("ペアリング待機（5分間公開）") { requestDiscoverable() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        root.addView(section("2. 接続先を選択"))
        val row = horizontal()
        val spinner = Spinner(this)
        devices = spinner
        row.addView(spinner, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(button("更新") { refreshDevices() })
        root.addView(row)
        val connectButton = button("選択したPCに接続") {
            if (hid.isConnecting()) hid.cancelConnection()
            else {
                val index = spinner.selectedItemPosition
                hid.connect(paired.getOrNull(index))
            }
        }
        connectionButton = connectButton
        root.addView(connectButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        root.addView(label("接続すると操作画面へ切り替わります。PCから自動接続される場合もあります。", 13, false).apply {
            setPadding(0, dp(12), 0, 0)
        })
        root.addView(button("プライバシーポリシー") { showPrivacyPolicy() })
        refreshDevices()
    }

    private fun showControls() {
        val root = FrameLayout(this).apply { setBackgroundColor(AppColors.BACKGROUND) }
        setInsetContentView(root)
        val content = column().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val keyboard = KleKeyboardView(this, hid, settings, object : KleKeyboardView.Listener {
            override fun bindingFor(key: KleLayout.Key): KeyBinding? = this@MainActivity.bindingFor(key)
            override fun hasLayerOverride(key: KleLayout.Key): Boolean = this@MainActivity.hasLayerOverride(key)
            override fun isLayerKeyActive(key: KleLayout.Key): Boolean = !bindingEditMode && key.index == activeLayerKeyIndex
            override fun isEditing(): Boolean = bindingEditMode
            override fun activeLayer(): Int = displayedLayer()
            override fun onKeyTap(key: KleLayout.Key, layer: Int) {
                if (bindingEditMode) {
                    if (!key.ghost) editBindingForLayer(key, bindingEditLayer)
                } else handleKey(key, layer)
            }
            override fun onMomentaryDown(key: KleLayout.Key) = beginMomentaryLayer(key)
            override fun onMomentaryUp() = endMomentaryLayer()
        })
        keyboardView = keyboard
        keyboard.setLayout(layout)
        keyboardViewState?.let { keyboard.restoreViewState(it) }
        keyboardViewState = null
        keyboard.setActiveModifiers(if (bindingEditMode) 0 else modifiers)
        keyboard.setLayer(displayedLayer())
        if (bindingEditMode) {
            val layerRow = horizontal()
            bindingLayerButtons = (0..2).map { layer ->
                button("レイヤー $layer") { selectBindingEditLayer(layer) }.also { control ->
                    layerRow.addView(control, LinearLayout.LayoutParams(0, dp(44), 1f))
                }
            }
            updateBindingLayerButtons()
            content.addView(layerRow)
            val actions = horizontal()
            actions.addView(button("キャンセル") { cancelBindingEdit() },
                LinearLayout.LayoutParams(0, dp(44), 1f))
            actions.addView(button("保存") { saveBindingEdit() },
                LinearLayout.LayoutParams(0, dp(44), 1f))
            content.addView(actions)
        }
        content.addView(keyboard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (!bindingEditMode) {
            val indicator = label("", 12, true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(AppColors.SURFACE)
                cornerRadius = dp(8).toFloat()
            }
            }
            layerIndicator = indicator
            updateLayerIndicator()
            root.addView(indicator, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.TOP).apply {
                leftMargin = dp(12); topMargin = dp(12)
            })
        }

        val edgeZone = FrameLayout(this)
        edgeZone.contentDescription = "右端中央から左へスワイプしてメニューを開く"
        val indicatorBackground = GradientDrawable().apply {
            setColor(AppColors.ACCENT)
            cornerRadius = dp(2).toFloat()
        }
        val indicatorParams = FrameLayout.LayoutParams(dp(3), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(2)
        }
        edgeZone.addView(View(this).apply { background = indicatorBackground }, indicatorParams)
        var swipeX = 0f; var swipeY = 0f
        edgeZone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { swipeX = event.rawX; swipeY = event.rawY }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - swipeX; val dy = event.rawY - swipeY
                    if (dx < -dp(42) && abs(dx) > abs(dy) * 1.25f) setMenuOpen(true)
                }
            }
            true
        }
        edgeZone.setOnClickListener { setMenuOpen(true) } // Accessibility action; touch taps are consumed above.
        menuHandle = edgeZone
        if (!bindingEditMode) root.addView(edgeZone,
            FrameLayout.LayoutParams(dp(24), dp(108), Gravity.END or Gravity.CENTER_VERTICAL))
        if (!bindingEditMode && Build.VERSION.SDK_INT >= 29) root.post {
            val top = (root.height - dp(108)) / 2
            root.systemGestureExclusionRects = listOf(Rect(root.width - dp(24), top, root.width, top + dp(108)))
        }

        val scrim = View(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setOnClickListener { setMenuOpen(false) }
            visibility = View.GONE
        }
        menuScrim = scrim
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val panel = column().apply { setPadding(dp(12), dp(16), dp(12), dp(12)) }
        menuPanel = panel
        panel.background = GradientDrawable().apply {
            setColor(AppColors.SURFACE)
            cornerRadius = dp(18).toFloat()
        }
        panel.elevation = dp(12).toFloat()
        panel.addView(label("Liki", 22, true))
        statusView = label(statusText, 13, false).also {
            it.setPadding(0, dp(8), 0, dp(4))
            panel.addView(it)
        }
        panel.addView(label(selectedSavedLayoutName ?: layout.name, 13, false).apply {
            setTextColor(AppColors.MUTED)
        })
        val actions = column()
        val actionScroll = ScrollView(this).apply {
            addView(actions)
        }
        panel.addView(actionScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        actions.addView(menuAction("キー割り当てを編集") { setMenuOpen(false); beginBindingEdit() })
        actions.addView(menuAction("レイアウトを選択") { setMenuOpen(false); chooseLayout() })
        actions.addView(menuAction("表示をリセット") {
            setMenuOpen(false)
            settings.setKeyPitchMm(0f)
            settings.clearAutoKeyScalePx()
            keyboard.resetZoom()
        })
        actions.addView(menuAction("設定") { showSettings() })
        actions.addView(menuAction("切断") {
            setMenuOpen(false)
            reconnectAddress = null
            reconnectAttempted = false
            hid.disconnect()
        })
        panel.visibility = View.GONE
        val menuParams = FrameLayout.LayoutParams(dp(216), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(12)
            topMargin = dp(12)
            bottomMargin = dp(12)
        }
        root.addView(panel, menuParams)
    }

    private fun menuAction(text: String, action: View.OnClickListener): Button = button(text, action).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        minHeight = dp(48)
    }

    private fun setMenuOpen(open: Boolean) {
        val panel = menuPanel ?: return
        menuHandle?.visibility = if (open) View.GONE else View.VISIBLE
        menuScrim?.visibility = if (open) View.VISIBLE else View.GONE
        panel.visibility = if (open) View.VISIBLE else View.GONE
    }

    private fun showSettings() {
        val currentPitchMm = keyboardView?.currentPitchMm() ?: 0f
        keyboardViewState = keyboardView?.viewState()
        settingsVisible = true
        keyboardView = null; menuHandle = null; menuScrim = null; menuPanel = null; statusView = null
        val scroll = ScrollView(this).apply { setBackgroundColor(AppColors.BACKGROUND) }
        val root = column().apply { setPadding(dp(20), dp(16), dp(20), dp(28)) }
        scroll.addView(root)
        setInsetContentView(scroll)
        root.addView(button("‹  操作画面に戻る") { showPage(hid.isConnected()) })
        root.addView(label("設定", 28, true).apply { setPadding(0, dp(20), 0, dp(4)) })
        root.addView(label("操作方法を切り替えます。変更はすぐに保存されます。", 14, false).apply {
            setTextColor(AppColors.MUTED)
        })
        root.addView(button("プライバシーポリシー") { showPrivacyPolicy() })
        root.addView(section("表示"))
        addOrientationSetting(root)
        addSetting(root, "画面を常にON", "Likiを開いている間は自動消灯しない", settings.keepScreenOn()) { _, enabled ->
            settings.setKeepScreenOn(enabled)
            applyKeepScreenOn()
        }
        root.addView(section("操作感"))
        addSetting(root, "タッチ時に振動", "キーのタップとトラックパッドのクリック時に振動", settings.touchVibration()) { _, enabled ->
            settings.setTouchVibration(enabled)
        }
        root.addView(section("キーボード"))
        addPitchSetting(root, currentPitchMm)
        addSetting(root, "KLE JSONファイルに従った表示",
            "オン: どのレイヤーでもKLEの文字を表示。オフ: 有効なキー割り当て名を表示",
            settings.kleJsonKeyLabels()) { _, enabled ->
            settings.setKleJsonKeyLabels(enabled)
        }
        addSetting(root, "修飾キーを保持", "Ctrl・Shiftなどを次のキー入力まで保持", settings.stickyModifiers()) { _, enabled ->
            settings.setStickyModifiers(enabled)
            if (!enabled) modifiers = 0
        }
        addSetting(root, "ピンチで拡大縮小", "2本指でキーボードの表示倍率を変更", settings.pinchZoom()) { _, enabled ->
            settings.setPinchZoom(enabled)
        }
        addSetting(root, "ドラッグでキーボードを移動", "通常キーの上を1本指で動かして表示位置を変更", settings.dragMoveKeyboard()) { _, enabled ->
            settings.setDragMoveKeyboard(enabled)
        }
        root.addView(section("トラックパッド"))
        addSpeedSetting(root, "ポインターの速度", settings.pointerSpeed(), settings::setPointerSpeed)
        addPointerSendModeSetting(root)
        addSetting(root, "タップでクリック", "1本指でタップして左クリック", settings.tapToClick()) { _, enabled ->
            settings.setTapToClick(enabled)
        }
        addSetting(root, "ダブルタップでドラッグ", "タップでクリックを有効にして、2回目のタップ後に指を動かす", settings.tapDrag()) { _, enabled ->
            settings.setTapDrag(enabled)
        }
        addSetting(root, "長押しでドラッグ", "1本指で押し続けてドラッグ", settings.holdToDrag()) { _, enabled ->
            settings.setHoldToDrag(enabled)
        }
        addSetting(root, "2本指タップで右クリック", "動かさずに2本指でタップ", settings.twoFingerRightClick()) { _, enabled ->
            settings.setTwoFingerRightClick(enabled)
        }
        addSetting(root, "2本指でスクロール", "トラックパッドを2本指で上下に動かす", settings.twoFingerScroll()) { _, enabled ->
            settings.setTwoFingerScroll(enabled)
        }
        addSetting(root, "ピンチでズーム", "対応するPC画面へCtrl＋ホイールとして送信", settings.trackpadPinchZoom()) { _, enabled ->
            settings.setTrackpadPinchZoom(enabled)
        }
        addSetting(root, "3本指タップで中クリック", "動かさずに3本指でタップ", settings.threeFingerMiddleClick()) { _, enabled ->
            settings.setThreeFingerMiddleClick(enabled)
        }
        addSpeedSetting(root, "スクロール速度", settings.scrollSpeed(), settings::setScrollSpeed)
        addSetting(root, "スクロール方向を反転", "2本指で動かしたときのスクロール方向を逆にする", settings.reverseScroll()) { _, enabled ->
            settings.setReverseScroll(enabled)
        }
    }

    private fun addOrientationSetting(root: LinearLayout) {
        val options = arrayOf("自動（端末の設定に従う）", "縦向き", "横向き")
        val item = column().apply { setPadding(0, dp(8), 0, dp(12)) }
        val choice = button("画面の向き: ${options[settings.screenOrientation()]}") {}
        choice.setOnClickListener {
            AlertDialog.Builder(this).setTitle("画面の向き")
                .setSingleChoiceItems(options, settings.screenOrientation()) { dialog, which ->
                    settings.setScreenOrientation(which)
                    choice.text = "画面の向き: ${options[which]}"
                    dialog.dismiss()
                    applyScreenOrientation()
                }.setNegativeButton("キャンセル", null).show()
        }
        item.addView(choice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(item)
    }

    private fun addPointerSendModeSetting(root: LinearLayout) {
        val options = arrayOf("即時（毎回送信）", "16ms（送信多め）", "32ms（標準）")
        val item = column().apply { setPadding(0, dp(8), 0, dp(12)) }
        val choice = button("カーソルの送信間隔: ${options[settings.pointerSendMode()]}") {}
        choice.setOnClickListener {
            AlertDialog.Builder(this).setTitle("カーソルの送信間隔")
                .setSingleChoiceItems(options, settings.pointerSendMode()) { dialog, which ->
                    settings.setPointerSendMode(which)
                    choice.text = "カーソルの送信間隔: ${options[which]}"
                    dialog.dismiss()
                }.setNegativeButton("キャンセル", null).show()
        }
        item.addView(choice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        item.addView(label("同じ速さのまま、カーソル移動の送信頻度を変えられます。", 13, false).apply {
            setTextColor(AppColors.MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        root.addView(item)
    }

    private fun applyScreenOrientation() {
        requestedOrientation = when (if (showingControls) settings.screenOrientation() else 0) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyKeepScreenOn() {
        if (settings.keepScreenOn()) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun addSpeedSetting(root: LinearLayout, title: String, speed: Float, save: (Float) -> Unit) {
        val item = column().apply { setPadding(0, dp(8), 0, dp(12)) }
        val value = label(String.format(Locale.JAPAN, "%s: %.1f倍", title, speed), 16, false)
        val slider = SeekBar(this).apply {
            max = 25 // 0.5–3.0 times in 0.1 steps.
            progress = ((speed - 0.5f) * 10).roundToInt().coerceIn(0, max)
        }
        item.addView(value)
        item.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(item)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val selected = 0.5f + progress / 10f
                value.text = String.format(Locale.JAPAN, "%s: %.1f倍", title, selected)
                if (fromUser) save(selected)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    private fun addPitchSetting(root: LinearLayout, currentPitchMm: Float) {
        val startingPitch = (currentPitchMm.takeIf { it.isFinite() && it > 0 }
            ?: settings.keyPitchMm().takeIf { it.isFinite() && it > 0 } ?: 6f).coerceIn(3f, 24f)
        val slider = SeekBar(this).apply {
            max = 210 // 3.0–24.0 mm in 0.1 mm steps.
            progress = ((startingPitch - 3f) * 10).roundToInt()
        }
        val selected = label("キーピッチ: ${pitchText(3f + slider.progress / 10f)}", 16, true)
        root.addView(selected)
        root.addView(label("端末の画面密度から換算した概算値です。バーで1キー分の横幅を指定します。画面に収めるにはメニューの「表示をリセット」を押します。", 13, false).apply {
            setTextColor(AppColors.MUTED)
            setPadding(0, dp(4), 0, dp(10))
        })
        root.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val pitch = 3f + progress / 10f
                selected.text = "キーピッチ: ${pitchText(pitch)}"
                if (fromUser) settings.setKeyPitchMm(pitch)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    private fun pitchText(mm: Float): String =
        if (mm > 0 && mm.isFinite()) String.format(Locale.JAPAN, "約 %.1f mm", mm) else "取得できません"

    private fun addSetting(root: LinearLayout, title: String, detail: String, enabled: Boolean,
        listener: CompoundButton.OnCheckedChangeListener) {
        val item = column().apply { setPadding(0, dp(8), 0, dp(12)) }
        val toggle = Switch(this).apply {
            text = title
            textSize = 16f
            setTextColor(AppColors.TEXT)
            isChecked = enabled
            setOnCheckedChangeListener(listener)
        }
        item.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        item.addView(label(detail, 13, false).apply {
            setTextColor(AppColors.MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        root.addView(item)
    }

    @Deprecated("Used by Android versions before predictive back")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    private fun handleBack(): Boolean {
        if (menuPanel?.visibility == View.VISIBLE) setMenuOpen(false)
        else if (bindingEditMode) cancelBindingEdit()
        else if (settingsVisible) showPage(hid.isConnected())
        else return false
        return true
    }

    private fun handleKey(key: KleLayout.Key, layer: Int = activeLayer) {
        val binding = bindingFor(key, layer)
        if (binding == null) { toast("メニューのキー割り当て編集で設定してください"); return }
        when (binding.layerAction) {
            LayerAction.BASE -> { setActiveLayer(0); return }
            LayerAction.TOGGLE -> {
                val target = if (activeLayer == binding.layer) 0 else binding.layer
                setActiveLayer(target)
                if (target != 0) markLayerKeyActive(key.index)
                return
            }
            LayerAction.CYCLE -> {
                setActiveLayer((activeLayer + 1) % 3)
                if (activeLayer != 0) markLayerKeyActive(key.index)
                return
            }
            LayerAction.ONE_SHOT -> {
                val previous = activeLayer
                val previousKey = activeLayerKeyIndex
                setActiveLayer(binding.layer)
                oneShotReturnLayer = if (previous == binding.layer) 0 else previous
                oneShotReturnKeyIndex = if (previous == binding.layer) null else previousKey
                markLayerKeyActive(key.index)
                updateLayerIndicator()
                return
            }
            LayerAction.MOMENTARY -> return // Handled by touch down and release.
            LayerAction.NONE -> Unit
        }
        if (binding.modifier != 0 && binding.code == 0) {
            if (settings.stickyModifiers()) modifiers = modifiers xor binding.modifier
            else hid.key(binding.modifier, 0)
        } else {
            if (binding.consumerUsage != 0) hid.consumer(binding.consumerUsage)
            else hid.key((if (settings.stickyModifiers()) modifiers else 0) or binding.modifier, binding.code)
            modifiers = 0
            oneShotReturnLayer?.let { previous ->
                val previousKey = oneShotReturnKeyIndex
                setActiveLayer(previous)
                markLayerKeyActive(previousKey)
            }
        }
        keyboardView?.setActiveModifiers(modifiers)
    }

    private fun beginMomentaryLayer(key: KleLayout.Key) {
        val binding = bindingFor(key) ?: return
        if (binding.layerAction != LayerAction.MOMENTARY) return
        val previous = activeLayer
        val previousKey = activeLayerKeyIndex
        setActiveLayer(binding.layer)
        momentaryPreviousLayer = previous
        momentaryPreviousKeyIndex = previousKey
        momentaryTargetLayer = binding.layer
        markLayerKeyActive(key.index)
    }

    private fun endMomentaryLayer() {
        val previous = momentaryPreviousLayer
        val previousKey = momentaryPreviousKeyIndex
        val target = momentaryTargetLayer
        momentaryPreviousLayer = null
        momentaryPreviousKeyIndex = null
        momentaryTargetLayer = null
        if (previous != null && activeLayer == target) {
            setActiveLayer(previous)
            markLayerKeyActive(previousKey)
        }
    }

    private fun setActiveLayer(layer: Int) {
        activeLayer = layer.coerceIn(0, 2)
        activeLayerKeyIndex = null
        oneShotReturnLayer = null
        oneShotReturnKeyIndex = null
        momentaryPreviousLayer = null
        momentaryPreviousKeyIndex = null
        momentaryTargetLayer = null
        modifiers = 0
        keyboardView?.setActiveModifiers(0)
        keyboardView?.setLayer(activeLayer)
        updateLayerIndicator()
    }

    private fun markLayerKeyActive(index: Int?) {
        activeLayerKeyIndex = index
        keyboardView?.invalidate()
    }

    private fun updateLayerIndicator() {
        layerIndicator?.text = "L$activeLayer${if (oneShotReturnLayer != null) "・1回" else ""}"
        layerIndicator?.contentDescription = "現在のレイヤー $activeLayer${if (oneShotReturnLayer != null) "、次の1キーだけ" else ""}"
    }

    private fun layerMappings(layer: Int): JSONObject? =
        if (layer == 0) overrides else layerOverrides.optJSONObject(layer.toString())

    private fun displayedLayer(): Int = if (bindingEditMode) bindingEditLayer else activeLayer

    private fun bindingFor(key: KleLayout.Key, layer: Int = displayedLayer()): KeyBinding? {
        val index = key.index.toString()
        if (layer != 0) {
            KeyBinding.named(layerMappings(layer)?.optString(index, "") ?: "")?.let { return it }
        }
        return KeyBinding.named(overrides.optString(index, "")) ?: KeyBinding.forKey(key)
    }

    private fun hasLayerOverride(key: KleLayout.Key): Boolean =
        layerMappings(displayedLayer())?.has(key.index.toString()) == true

    private fun beginBindingEdit() {
        bindingEditBaseSnapshot = overrides.toString()
        bindingEditLayersSnapshot = layerOverrides.toString()
        bindingEditLayer = activeLayer
        bindingEditMode = true
        keyboardViewState = keyboardView?.viewState()
        showPage(hid.isConnected())
    }

    private fun selectBindingEditLayer(layer: Int) {
        bindingEditLayer = layer.coerceIn(0, 2)
        updateBindingLayerButtons()
        keyboardView?.setLayer(bindingEditLayer)
    }

    private fun updateBindingLayerButtons() {
        bindingLayerButtons.forEachIndexed { index, control ->
            control.alpha = if (index == bindingEditLayer) 1f else .55f
            control.contentDescription = "レイヤー $index${if (index == bindingEditLayer) "、編集中" else ""}"
        }
    }

    private fun saveBindingEdit() {
        saveOverrides()
        bindingEditMode = false
        bindingEditBaseSnapshot = null
        bindingEditLayersSnapshot = null
        keyboardViewState = keyboardView?.viewState()
        showPage(hid.isConnected())
        toast("キー割り当てを保存しました")
    }

    private fun cancelBindingEditDraft() {
        overrides = JSONObject(bindingEditBaseSnapshot ?: "{}")
        layerOverrides = JSONObject(bindingEditLayersSnapshot ?: "{}")
        bindingEditMode = false
        bindingEditBaseSnapshot = null
        bindingEditLayersSnapshot = null
    }

    private fun cancelBindingEdit() {
        cancelBindingEditDraft()
        keyboardViewState = keyboardView?.viewState()
        showPage(hid.isConnected())
    }

    private fun editBindingForLayer(key: KleLayout.Key, layer: Int) {
        val options = KeyBinding.options()
        val names = options.map { it.name }.toTypedArray()
        val mappings = if (layer == 0) overrides else
            (layerOverrides.optJSONObject(layer.toString()) ?: JSONObject().also {
                layerOverrides.put(layer.toString(), it)
            })
        AlertDialog.Builder(this).setTitle("レイヤー$layer・「${key.displayLabel()}」の割り当て")
            .setItems(names) { _, which ->
                try { mappings.put(key.index.toString(), options[which].name) }
                catch (_: Exception) { return@setItems }
                keyboardView?.invalidate()
            }
            .setNeutralButton(if (layer == 0) "自動割り当て" else "ベースを継承") { _, _ ->
                mappings.remove(key.index.toString())
                keyboardView?.invalidate()
            }
            .setNegativeButton("閉じる", null).show()
    }

    private fun loadLayout() {
        var json = preferences.getString("kle_json", null)
        try {
            selectedSavedLayoutName = preferences.getString("selected_saved_layout_name", null)
            if (json == null) json = assets.open("default-kle.json").use(::readText)
            else if (selectedSavedLayoutName == null) {
                val upgraded = upgradeBundledLayout(json)
                if (upgraded != json) {
                    json = upgraded
                    preferences.edit().putString("kle_json", upgraded).apply()
                }
            }
            currentKleJson = requireNotNull(json)
            layout = KleLayout.parse(currentKleJson)
            overrides = JSONObject(preferences.getString("overrides", "{}") ?: "{}")
            layerOverrides = JSONObject(preferences.getString("layer_overrides", "{}") ?: "{}")
        } catch (_: Exception) {
            try {
                currentKleJson = assets.open("default-kle.json").use(::readText)
                layout = KleLayout.parse(currentKleJson)
                overrides = JSONObject()
                layerOverrides = JSONObject()
                selectedSavedLayoutName = null
            } catch (error: Exception) { throw IllegalStateException("標準レイアウトを読めません", error) }
        }
    }

    private fun upgradeBundledLayout(saved: String): String {
        val normalized = saved.replace("\r\n", "\n").trim()
        for ((oldAsset, newAsset) in listOf(
            "legacy-ghosted-trackpad.json" to "ghosted-trackpad.json",
            "legacy-default-kle.json" to "default-kle.json")) {
            val old = assets.open(oldAsset).use(::readText).replace("\r\n", "\n").trim()
            if (normalized == old) return assets.open(newAsset).use(::readText)
        }
        return saved
    }

    private fun openKlePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        startActivityForResult(intent, REQUEST_KLE)
    }

    private fun chooseLayout() {
        val saved = savedLayouts.all()
        val options = mutableListOf("標準トラックパッド", "標準キーボード（TKL）")
        saved.forEach { options.add("保存: ${it.name}") }
        val importIndex = options.size
        options.add("KLE JSONファイルを開く")
        val saveIndex = options.size
        options.add("現在のレイアウトに名前を付けて保存")
        val deleteIndex = options.size
        options.add("保存したレイアウトを削除")
        AlertDialog.Builder(this).setTitle("レイアウトを選択")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> loadBundled("ghosted-trackpad.json")
                    1 -> loadBundled("default-kle.json")
                    in 2 until importIndex -> restoreSavedLayout(saved[which - 2])
                    importIndex -> openKlePicker()
                    saveIndex -> promptSaveLayout()
                    deleteIndex -> chooseSavedLayoutToDelete()
                }
            }
            .setNegativeButton("閉じる", null).show()
    }

    private fun promptSaveLayout() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            setText(selectedSavedLayoutName ?: "")
            selectAll()
        }
        val dialog = AlertDialog.Builder(this).setTitle("レイアウトに名前を付けて保存")
            .setView(input)
            .setPositiveButton("保存", null)
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.length > 40) {
                    input.error = "名前は1〜40文字で入力してください"
                    return@setOnClickListener
                }
                val existing = savedLayouts.all().firstOrNull { it.name.equals(name, ignoreCase = true) }
                if (existing == null) {
                    saveCurrentLayout(name)
                    dialog.dismiss()
                } else {
                    AlertDialog.Builder(this).setTitle("「${existing.name}」を上書きしますか？")
                        .setPositiveButton("上書き") { _, _ ->
                            saveCurrentLayout(name)
                            dialog.dismiss()
                        }
                        .setNegativeButton("キャンセル", null).show()
                }
            }
        }
        dialog.show()
    }

    private fun saveCurrentLayout(name: String) {
        val viewState = keyboardView?.viewState() ?: keyboardViewState
            ?: KleKeyboardView.ViewState(1f, 0f, 0f)
        savedLayouts.save(SavedLayout(name, currentKleJson, overrides.toString(),
            layerOverrides.toString(), viewState))
        selectedSavedLayoutName = name
        preferences.edit().putString("selected_saved_layout_name", name).apply()
        showPage(hid.isConnected())
        toast("「$name」を保存しました")
    }

    private fun restoreSavedLayout(saved: SavedLayout) {
        try {
            val parsed = KleLayout.parse(saved.kleJson)
            val base = JSONObject(saved.overrides)
            val layers = JSONObject(saved.layerOverrides)
            layout = parsed; overrides = base; layerOverrides = layers
            currentKleJson = saved.kleJson
            modifiers = 0; activeLayer = 0; activeLayerKeyIndex = null
            oneShotReturnLayer = null; oneShotReturnKeyIndex = null
            momentaryPreviousLayer = null; momentaryPreviousKeyIndex = null; momentaryTargetLayer = null
            keyboardViewState = saved.viewState
            selectedSavedLayoutName = saved.name
            settings.clearAutoKeyScalePx()
            preferences.edit().putString("kle_json", saved.kleJson)
                .putString("overrides", saved.overrides)
                .putString("layer_overrides", saved.layerOverrides)
                .putString("selected_saved_layout_name", saved.name).apply()
            showPage(hid.isConnected(), preserveKeyboardViewState = false)
            toast("「${saved.name}」を読み込みました")
        } catch (error: Exception) { toast("保存したレイアウトを読めません: ${error.message}") }
    }

    private fun chooseSavedLayoutToDelete() {
        val saved = savedLayouts.all()
        if (saved.isEmpty()) { toast("保存したレイアウトはありません"); return }
        AlertDialog.Builder(this).setTitle("保存したレイアウトを削除")
            .setItems(saved.map { it.name }.toTypedArray()) { _, which ->
                val selected = saved[which]
                AlertDialog.Builder(this).setTitle("「${selected.name}」を削除しますか？")
                    .setMessage("現在の画面はそのまま残ります。")
                    .setPositiveButton("削除") { _, _ ->
                        savedLayouts.delete(selected.name)
                        if (selectedSavedLayoutName?.equals(selected.name, ignoreCase = true) == true) {
                            selectedSavedLayoutName = null
                            preferences.edit().remove("selected_saved_layout_name").apply()
                            showPage(hid.isConnected())
                        }
                        toast("「${selected.name}」を削除しました")
                    }
                    .setNegativeButton("キャンセル", null).show()
            }
            .setNegativeButton("閉じる", null).show()
    }

    private fun loadBundled(asset: String) {
        try { assets.open(asset).use { applyLayout(readText(it)) } }
        catch (error: Exception) { toast("レイアウトを読み込めません: ${error.message}") }
    }

    private fun importKle(data: Intent) {
        try {
            val uri = data.data ?: throw IllegalArgumentException("ファイルを開けません")
            val input = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("ファイルを開けません")
            input.use { applyLayout(readText(it)) }
        } catch (error: Exception) {
            AlertDialog.Builder(this).setTitle("KLE JSONを読み込めません")
                .setMessage(error.message).setPositiveButton("閉じる", null).show()
        }
    }

    private fun applyLayout(json: String) {
        val parsed = KleLayout.parse(json)
        layout = parsed; overrides = JSONObject(); layerOverrides = JSONObject()
        currentKleJson = json
        selectedSavedLayoutName = null
        modifiers = 0; activeLayer = 0; activeLayerKeyIndex = null
        oneShotReturnLayer = null; oneShotReturnKeyIndex = null
        momentaryPreviousLayer = null; momentaryPreviousKeyIndex = null; momentaryTargetLayer = null
        settings.clearAutoKeyScalePx()
        keyboardViewState = null
        preferences.edit().putString("kle_json", json).putString("overrides", "{}")
            .putString("layer_overrides", "{}").remove("selected_saved_layout_name").apply()
        showPage(hid.isConnected(), preserveKeyboardViewState = false)
        toast("${parsed.keys.size}キーのレイアウトを読み込みました")
    }

    private fun readText(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (output.size() + count > 1024 * 1024) throw IllegalArgumentException("ファイルは1MB以下にしてください")
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun saveOverrides() {
        preferences.edit().putString("overrides", overrides.toString())
            .putString("layer_overrides", layerOverrides.toString()).apply()
    }

    private fun ensureReady() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CONNECT)
        else if (!hid.isBluetoothReady()) startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE)
        else { hid.start(); refreshDevices() }
    }

    private fun maybeReconnect() {
        val address = reconnectAddress ?: return
        if (!activityVisible || reconnectAttempted || !hid.isRegistered() ||
            hid.isConnected() || hid.isConnecting() || hid.isCancelling()) return
        val device = hid.pairedDevices().firstOrNull { it.address == address }
        if (device == null) {
            reconnectAttempted = true
            if (showingControls) showPage(false)
            return
        }
        reconnectAttempted = true
        hid.connect(device)
    }

    private fun restoringConnection(): Boolean = reconnectAddress != null && !hid.isConnected() &&
        (!activityVisible || !reconnectAttempted || hid.isConnecting())

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        val granted = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQUEST_CONNECT -> if (granted) ensureReady() else statusText = "Bluetooth接続権限が必要です"
            REQUEST_ADVERTISE -> if (granted) requestDiscoverable() else toast("Bluetooth公開権限が必要です")
        }
        updateStatus()
    }

    @Deprecated("Uses the existing document picker flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when {
            requestCode == REQUEST_ENABLE && resultCode == RESULT_OK -> ensureReady()
            requestCode == REQUEST_ENABLE -> { statusText = "Bluetoothをオンにしてください"; updateStatus() }
            requestCode == REQUEST_DISCOVERABLE && resultCode > 0 -> {
                statusText = "公開中です。PCからペアリングしてください"; updateStatus()
            }
            requestCode == REQUEST_KLE && resultCode == RESULT_OK && data != null -> importKle(data)
        }
    }

    private fun requestDiscoverable() {
        if (!hid.isRegistered()) { toast("HIDの準備が完了してから再試行してください"); return }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE), REQUEST_ADVERTISE)
            return
        }
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        startActivityForResult(intent, REQUEST_DISCOVERABLE)
    }

    private fun refreshDevices() {
        val spinner = devices ?: return
        var selectedAddress: String? = paired.getOrNull(spinner.selectedItemPosition)?.address
        selectedAddress = RecentDevice.preferredAddress(preferences, selectedAddress)
        paired.clear(); paired.addAll(hid.pairedDevices())
        val names = paired.map { it.name?.takeIf(String::isNotEmpty) ?: it.address }.toMutableList()
        val selectedIndex = RecentDevice.indexOf(paired, selectedAddress)
        if (names.isEmpty()) names.add("ペアリング済みのPCがありません")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        if (selectedIndex >= 0) spinner.setSelection(selectedIndex)
    }

    private fun updateStatus() {
        val status = statusView ?: return
        val connected = hid.isConnected()
        status.text = (if (connected) "● " else "○ ") + statusText
        status.setTextColor(if (connected) AppColors.ACCENT else AppColors.MUTED)
        connectionButton?.let { button ->
            val cancelling = hid.isCancelling()
            button.text = when {
                cancelling -> "キャンセル中…"
                hid.isConnecting() -> "接続をキャンセル"
                else -> "選択したPCに接続"
            }
            button.isEnabled = !cancelling
            devices?.isEnabled = !cancelling && !hid.isConnecting()
        }
    }

    private fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun showPrivacyPolicy() {
        AlertDialog.Builder(this)
            .setTitle("プライバシーポリシー")
            .setMessage(getString(R.string.privacy_policy_text))
            .setPositiveButton("閉じる", null)
            .show()
    }
    private fun setInsetContentView(view: View) {
        if (Build.VERSION.SDK_INT >= 35) {
            val left = view.paddingLeft
            val top = view.paddingTop
            val right = view.paddingRight
            val bottom = view.paddingBottom
            view.setOnApplyWindowInsetsListener { content, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                content.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
                insets
            }
        }
        setContentView(view)
    }
    private fun horizontal(): LinearLayout = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun section(text: String): TextView = label(text, 20, true).apply { setPadding(0, dp(24), 0, dp(8)) }
    private fun label(text: String, size: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(AppColors.TEXT)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun button(text: String, click: View.OnClickListener? = null): Button = Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        if (click != null) setOnClickListener(click)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
}
