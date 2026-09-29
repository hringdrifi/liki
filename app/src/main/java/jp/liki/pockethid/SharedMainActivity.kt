package jp.liki.pockethid

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Rect
import android.view.WindowManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.View
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jp.liki.shared.HidPort
import jp.liki.shared.LikiApp
import jp.liki.shared.UiKey
import jp.liki.shared.UiLayout
import jp.liki.shared.UiSlider
import jp.liki.shared.UiToggle
import jp.liki.shared.UiViewState
import jp.liki.shared.UiBindingChoice
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/** Android entry point for the shared UI. Bluetooth HID stays in Android code. */
class SharedMainActivity : ComponentActivity() {
    private companion object {
        const val REQUEST_BLUETOOTH = 100
        const val REQUEST_ENABLE = 101
        const val REQUEST_DISCOVERABLE = 102
        const val REQUEST_KLE = 103
    }

    private lateinit var port: AndroidHidPort
    private var reconnectAddress: String? = null
    private var reconnectAttempted = false
    private var afterPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reconnectAddress = savedInstanceState?.getString("reconnect_address")
        port = AndroidHidPort()
        savedInstanceState?.let { port.restoreState(it) }
        setContent { LikiApp(port) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (port.backHandler?.invoke() != true) finish()
            }
        })
        if (Build.VERSION.SDK_INT >= 29) {
            window.decorView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val density = resources.displayMetrics.density
                val edgeWidth = (32 * density).toInt()
                val edgeHeight = (108 * density).toInt()
                val centerY = view.height / 2
                view.systemGestureExclusionRects = listOf(Rect(
                    view.width - edgeWidth, centerY - edgeHeight / 2,
                    view.width, centerY + edgeHeight / 2))
            }
        }
        applyDeviceSettings()
        ensureReady()
    }

    override fun onResume() {
        super.onResume()
        if (::port.isInitialized) {
            applyDeviceSettings()
            ensureReady()
        }
    }

    override fun onPause() {
        if (::port.isInitialized) reconnectAddress = port.connectedAddress() ?: reconnectAddress
        reconnectAttempted = false
        super.onPause()
    }

    override fun onDestroy() {
        if (::port.isInitialized) port.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("reconnect_address", port.connectedAddress() ?: reconnectAddress)
        port.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun withBluetoothPermissions(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31) {
            val missing = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                afterPermission = action
                requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
                return
            }
        }
        action()
    }

    private fun ensureReady() = withBluetoothPermissions {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { port.status = "この端末はBluetoothに対応していません"; return@withBluetoothPermissions }
        if (!adapter.isEnabled) {
            port.status = "Bluetoothをオンにしてください"
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE)
            return@withBluetoothPermissions
        }
        port.start()
    }

    private fun applyDeviceSettings() {
        val settings = AppSettings(this)
        if (settings.keepScreenOn()) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = if (!::port.isInitialized || !port.connected) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else when (settings.screenOrientation()) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            3 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val fullscreen = ::port.isInitialized && port.connected && !port.settingsVisible &&
            settings.fullscreenControls()
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(!fullscreen && Build.VERSION.SDK_INT < 35)
            window.decorView.windowInsetsController?.apply {
                if (fullscreen) {
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsets.Type.systemBars())
                } else show(WindowInsets.Type.systemBars())
            }
        } else window.decorView.systemUiVisibility = if (fullscreen)
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        else 0
    }

    private fun vibrateTouch() {
        if (AppSettings(this).touchVibration())
            window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        val action = afterPermission
        afterPermission = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) action?.invoke()
        else port.status = "Bluetooth接続と公開の権限が必要です"
    }

    @Deprecated("Android activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE && resultCode == RESULT_OK) port.start()
        if (requestCode == REQUEST_DISCOVERABLE) port.refreshDevices()
        if (requestCode == REQUEST_KLE && resultCode == RESULT_OK) {
            runCatching {
                val uri = data?.data ?: error("ファイルを開けません")
                val json = contentResolver.openInputStream(uri)?.use(::readKleText)
                    ?: error("ファイルを開けません")
                port.loadImported(json)
            }.onFailure { port.status = "KLE JSONを読み込めません: ${it.message}" }
        }
    }

    private fun readKleText(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            require(output.size() + count <= 1024 * 1024) { "ファイルは1MB以下にしてください" }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private inner class AndroidHidPort : HidPort {
        override var status by mutableStateOf("準備中…")
        override var connected by mutableStateOf(false)
        override var connecting by mutableStateOf(false)
        override var devices by mutableStateOf(emptyList<String>())
        override var layout by mutableStateOf(UiLayout("キーボード", emptyList()))
        override var viewState by mutableStateOf(UiViewState(1f, 0f, 0f))
        override var viewRevision by mutableIntStateOf(0)
        override var activeLayer by mutableIntStateOf(0)
        override val oneShotActive: Boolean get() = oneShotPrevious != null
        override var editLayer by mutableIntStateOf(0)
        override var editing by mutableStateOf(false)
        override var settingsVisible: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                applyDeviceSettings()
            }
        override var backHandler: (() -> Boolean)? = null
        override var layoutChoices by mutableStateOf(emptyList<String>())
        override val bindingChoices: List<UiBindingChoice> = KeyBinding.options().map { binding ->
            val category = when {
                binding.layerAction != LayerAction.NONE -> "レイヤー"
                binding.consumerUsage != 0 -> "メディアキー"
                binding.code == 0 && binding.modifier != 0 -> "修飾キー単体"
                binding.code != 0 && binding.modifier != 0 -> "ショートカット"
                binding.code in 4..39 -> "文字・数字"
                binding.code in 45..56 -> "記号"
                binding.code in 40..44 || binding.code == 57 -> "基本キー"
                binding.code in 58..72 -> "ファンクションキー"
                binding.code in 73..82 || binding.code == 101 -> "移動・編集"
                binding.code in 83..99 -> "テンキー"
                else -> "その他"
            }
            UiBindingChoice(binding.name, category)
        }
        override var toggles by mutableStateOf(emptyList<UiToggle>())
        override var sliders by mutableStateOf(emptyList<UiSlider>())
        override var layerColors by mutableStateOf(emptyList<Int>())
        override var autoKeyScaleDp by mutableStateOf(0f)
        override val keyPitchDpPerMm: Float = resources.displayMetrics.let { metrics ->
            (metrics.xdpi.takeIf { it > 0f } ?: metrics.density * 160f) / metrics.density / 25.4f
        }
        override val privacyText: String = getString(R.string.privacy_policy_text)
        private var paired: List<BluetoothDevice> = emptyList()
        private val preferences = getSharedPreferences("layout", MODE_PRIVATE)
        private val settings = AppSettings(this@SharedMainActivity)
        private val savedLayouts = SavedLayoutStore(preferences)
        private var kleLayout: KleLayout
        private var currentKleJson: String
        private var baseBindings: JSONObject
        private var layerBindings: JSONObject
        private var baseSnapshot = "{}"
        private var layerSnapshot = "{}"
        private var modifiers = 0
        private var oneShotPrevious by mutableStateOf<Int?>(null)
        private var oneShotPreviousKey: Int? = null
        private var activeLayerKeyIndex: Int? = null
        private var momentaryPrevious: Int? = null
        private var momentaryPreviousKey: Int? = null
        private var momentaryTarget: Int? = null
        private lateinit var hid: HidController

        init {
            val savedJson = preferences.getString("kle_json", null)
            val defaultJson = assets.open("default-kle.json").bufferedReader().use { it.readText() }
            val candidate = if (savedJson != null &&
                preferences.getString("selected_saved_layout_name", null) == null)
                upgradeBundledLayout(savedJson) else savedJson ?: defaultJson
            if (savedJson != null && candidate != savedJson)
                preferences.edit().putString("kle_json", candidate).apply()
            currentKleJson = runCatching { KleLayout.parse(candidate); candidate }.getOrElse { defaultJson }
            kleLayout = KleLayout.parse(currentKleJson)
            baseBindings = runCatching { JSONObject(preferences.getString("overrides", "{}") ?: "{}") }
                .getOrElse { JSONObject() }
            layerBindings = runCatching { JSONObject(preferences.getString("layer_overrides", "{}") ?: "{}") }
                .getOrElse { JSONObject() }
            migrateBindingSchema()
            updateLayout()
            updateLayoutChoices()
            updateSettings()
            hid = HidController(this@SharedMainActivity, settings) { message, isConnected ->
                runOnUiThread {
                    if (isConnected != hid.isConnected()) return@runOnUiThread
                    status = message
                    connected = isConnected
                    connecting = hid.isConnecting()
                    applyDeviceSettings()
                    if (isConnected) {
                        hid.connectedAddress()?.let { RecentDevice.remember(preferences, it) }
                        reconnectAddress = null
                    } else {
                        refreshDevices()
                        maybeReconnect()
                    }
                }
            }
        }

        private fun upgradeBundledLayout(json: String): String {
            val normalized = json.replace("\r\n", "\n").trim()
            for ((oldAsset, newAsset) in listOf(
                "labeled-ghosted-trackpad.json" to "ghosted-trackpad.json",
                "legacy-ghosted-trackpad.json" to "ghosted-trackpad.json",
                "legacy-default-kle.json" to "default-kle.json")) {
                val old = assets.open(oldAsset).bufferedReader().use { it.readText() }
                if (old.replace("\r\n", "\n").trim() == normalized)
                    return assets.open(newAsset).bufferedReader().use { it.readText() }
            }
            return json
        }

        private fun migrateBindingSchema() {
            if (preferences.getInt("binding_schema_version", 0) >= 1) return
            baseBindings = materializeBindings(currentKleJson, baseBindings)
            for (saved in savedLayouts.all()) {
                runCatching {
                    val explicit = materializeBindings(saved.kleJson, JSONObject(saved.overrides))
                    if (explicit.toString() != saved.overrides)
                        savedLayouts.save(saved.copy(overrides = explicit.toString()))
                }
            }
            preferences.edit().putString("overrides", baseBindings.toString())
                .putInt("binding_schema_version", 1).apply()
        }

        private fun materializeBindings(json: String, mappings: JSONObject): JSONObject {
            val parsed = KleLayout.parse(json)
            val normalized = json.replace("\r\n", "\n").trim()
            val bundled = listOf(
                "ghosted-trackpad.json" to BundledBindings.trackpad,
                "labeled-ghosted-trackpad.json" to BundledBindings.trackpad,
                "legacy-ghosted-trackpad.json" to BundledBindings.trackpad,
                "default-kle.json" to BundledBindings.keyboard,
                "legacy-default-kle.json" to BundledBindings.keyboard
            ).firstOrNull { (asset, _) ->
                assets.open(asset).bufferedReader().use { it.readText() }
                    .replace("\r\n", "\n").trim() == normalized
            }?.second
            if (bundled != null && bundled.size == parsed.keys.size)
                return BundledBindings.into(mappings, bundled)
            parsed.keys.forEach { key ->
                if (!mappings.has(key.index.toString()))
                    KeyBinding.fromKleLabels(key)?.let { mappings.put(key.index.toString(), it.name) }
            }
            return mappings
        }

        fun start() {
            hid.start()
            refreshDevices()
            maybeReconnect()
        }

        fun connectedAddress(): String? = hid.connectedAddress()

        fun saveState(out: Bundle) {
            out.putFloat("keyboard_zoom", viewState.zoom)
            out.putFloat("keyboard_pan_x", viewState.panX)
            out.putFloat("keyboard_pan_y", viewState.panY)
            out.putInt("active_layer", momentaryPrevious ?: activeLayer)
            out.putInt("active_layer_key", if (momentaryPrevious != null)
                momentaryPreviousKey ?: -1 else activeLayerKeyIndex ?: -1)
            out.putInt("sticky_modifiers", modifiers)
            out.putInt("one_shot_previous", oneShotPrevious ?: -1)
            out.putInt("one_shot_previous_key", oneShotPreviousKey ?: -1)
            out.putBoolean("binding_edit_mode", editing)
            if (editing) {
                out.putInt("binding_edit_layer", editLayer)
                out.putString("binding_base_draft", baseBindings.toString())
                out.putString("binding_layer_draft", layerBindings.toString())
                out.putString("binding_base_snapshot", baseSnapshot)
                out.putString("binding_layer_snapshot", layerSnapshot)
            }
        }

        fun restoreState(state: Bundle) {
            if (state.containsKey("keyboard_zoom")) {
                viewState = UiViewState(state.getFloat("keyboard_zoom"),
                    state.getFloat("keyboard_pan_x"), state.getFloat("keyboard_pan_y"))
                viewRevision++
            }
            activeLayer = state.getInt("active_layer", 0).coerceIn(0, 3)
            activeLayerKeyIndex = state.getInt("active_layer_key", -1).takeIf { it >= 0 }
            modifiers = state.getInt("sticky_modifiers", 0)
            oneShotPrevious = state.getInt("one_shot_previous", -1).takeIf { it in 0..3 }
            oneShotPreviousKey = state.getInt("one_shot_previous_key", -1).takeIf { it >= 0 }
            if (state.getBoolean("binding_edit_mode")) {
                runCatching {
                    editLayer = state.getInt("binding_edit_layer", activeLayer).coerceIn(0, 3)
                    baseBindings = JSONObject(state.getString("binding_base_draft") ?: "{}")
                    layerBindings = JSONObject(state.getString("binding_layer_draft") ?: "{}")
                    baseSnapshot = state.getString("binding_base_snapshot") ?: baseBindings.toString()
                    layerSnapshot = state.getString("binding_layer_snapshot") ?: layerBindings.toString()
                    editing = true
                }.onFailure { editing = false }
            }
            updateLayout()
        }

        private fun maybeReconnect() {
            val address = reconnectAddress ?: return
            if (reconnectAttempted || !hid.isRegistered() || hid.isConnected() || hid.isConnecting()) return
            reconnectAttempted = true
            val device = paired.firstOrNull { it.address == address }
            if (device != null) connect(paired.indexOf(device))
        }

        fun close() = hid.close()

        override fun startPairing() = withBluetoothPermissions {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            startActivityForResult(intent, REQUEST_DISCOVERABLE)
        }

        override fun refreshDevices() {
            try {
                paired = hid.pairedDevices()
                val recent = RecentDevice.preferredAddress(preferences, null)
                paired = paired.sortedBy { if (it.address == recent) 0 else 1 }
                devices = paired.map { (it.name?.takeIf(String::isNotBlank) ?: it.address) +
                    if (it.address == recent) "（前回）" else "" }
            } catch (_: SecurityException) {
                paired = emptyList()
                devices = emptyList()
                status = "Bluetooth接続の権限が必要です"
            }
        }

        override fun connect(index: Int) {
            hid.connect(paired.getOrNull(index))
            connecting = hid.isConnecting()
        }

        override fun cancelConnection() {
            hid.cancelConnection()
            connecting = hid.isConnecting()
        }

        override fun disconnect() {
            reconnectAddress = null
            reconnectAttempted = false
            hid.disconnect()
        }

        override fun openAdvanced() {
            hid.close()
            connected = false
            startActivity(Intent(this@SharedMainActivity, MainActivity::class.java))
        }

        override fun key(modifiers: Int, usage: Int) = hid.key(modifiers, usage)
        override fun tapKey(index: Int) = tapKeyAtLayer(index, activeLayer)

        override fun tapKeyAtLayer(index: Int, layer: Int) {
            val key = kleLayout.keys.getOrNull(index) ?: return
            val binding = bindingFor(key, layer) ?: return
            vibrateTouch()
            when (binding.layerAction) {
                LayerAction.BASE -> selectLayer(0)
                LayerAction.TOGGLE -> {
                    val target = if (activeLayer == binding.layer) 0 else binding.layer
                    selectLayer(target)
                    if (target != 0) { activeLayerKeyIndex = index; updateLayout() }
                }
                LayerAction.CYCLE -> {
                    selectLayer((activeLayer + 1) % 4)
                    if (activeLayer != 0) { activeLayerKeyIndex = index; updateLayout() }
                }
                LayerAction.ONE_SHOT -> {
                    val previous = activeLayer
                    val previousKey = activeLayerKeyIndex
                    selectLayer(binding.layer)
                    oneShotPrevious = if (previous == binding.layer) 0 else previous
                    oneShotPreviousKey = if (previous == binding.layer) null else previousKey
                    activeLayerKeyIndex = index
                    updateLayout()
                }
                LayerAction.MOMENTARY -> Unit
                LayerAction.NONE -> {
                    if (binding.modifier != 0 && binding.code == 0) {
                        if (settings.stickyModifiers()) modifiers = modifiers xor binding.modifier
                        else hid.key(binding.modifier, 0)
                    } else {
                        if (binding.consumerUsage != 0) hid.consumer(binding.consumerUsage)
                        else hid.key((if (settings.stickyModifiers()) modifiers else 0) or
                            binding.modifier, binding.code)
                        modifiers = 0
                        oneShotPrevious?.let { previous ->
                            val previousKey = oneShotPreviousKey
                            selectLayer(previous)
                            activeLayerKeyIndex = previousKey
                        }
                    }
                    updateLayout()
                }
            }
        }

        override fun momentaryDown(index: Int) {
            val binding = kleLayout.keys.getOrNull(index)?.let(::bindingFor) ?: return
            if (binding.layerAction != LayerAction.MOMENTARY) return
            vibrateTouch()
            val previous = activeLayer
            val previousKey = activeLayerKeyIndex
            selectLayer(binding.layer)
            momentaryPrevious = previous
            momentaryPreviousKey = previousKey
            momentaryTarget = binding.layer
            activeLayerKeyIndex = index
            updateLayout()
        }

        override fun momentaryUp() {
            val previous = momentaryPrevious
            val previousKey = momentaryPreviousKey
            val target = momentaryTarget
            momentaryPrevious = null
            momentaryPreviousKey = null
            momentaryTarget = null
            if (previous != null && activeLayer == target) {
                selectLayer(previous)
                activeLayerKeyIndex = previousKey
            }
            updateLayout()
        }

        override fun updateViewState(state: UiViewState) { viewState = state }
        override fun resetView() {
            viewState = UiViewState(1f, 0f, 0f)
            settings.setKeyPitchMm(0f)
            settings.clearAutoKeyScalePx()
            viewRevision++
            updateSettings()
        }

        override fun selectLayer(layer: Int) {
            activeLayer = layer.coerceIn(0, 3)
            activeLayerKeyIndex = null
            oneShotPrevious = null
            oneShotPreviousKey = null
            momentaryPrevious = null
            momentaryPreviousKey = null
            momentaryTarget = null
            modifiers = 0
            updateLayout()
        }

        override fun selectEditLayer(layer: Int) {
            editLayer = layer.coerceIn(0, 3)
            updateLayout()
        }

        override fun selectLayout(index: Int) {
            when (index) {
                0 -> loadBundled("ghosted-trackpad.json", BundledBindings.trackpad)
                1 -> loadBundled("default-kle.json", BundledBindings.keyboard)
                else -> savedLayouts.all().getOrNull(index - 2)?.let { saved ->
                    runCatching {
                        kleLayout = KleLayout.parse(saved.kleJson)
                        currentKleJson = saved.kleJson
                        baseBindings = JSONObject(saved.overrides)
                        layerBindings = JSONObject(saved.layerOverrides)
                        persistLayout(saved.name)
                        viewState = UiViewState(saved.viewState.zoom, saved.viewState.panX, saved.viewState.panY)
                        viewRevision++
                    }.onFailure { status = "レイアウトを読み込めません: ${it.message}" }
                }
            }
        }

        override fun saveLayout(name: String) {
            val clean = name.trim().take(40)
            if (clean.isEmpty()) return
            savedLayouts.save(SavedLayout(clean, currentKleJson, baseBindings.toString(),
                layerBindings.toString(), KleKeyboardView.ViewState(viewState.zoom, viewState.panX, viewState.panY)))
            preferences.edit().putString("selected_saved_layout_name", clean).apply()
            updateLayoutChoices()
            status = "「$clean」を保存しました"
        }

        override fun deleteLayout(index: Int) {
            val saved = savedLayouts.all().getOrNull(index - 2) ?: return
            savedLayouts.delete(saved.name)
            if (preferences.getString("selected_saved_layout_name", null)
                    ?.equals(saved.name, ignoreCase = true) == true)
                preferences.edit().remove("selected_saved_layout_name").apply()
            updateLayoutChoices()
            status = "「${saved.name}」を削除しました"
        }

        override fun importLayout() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain",
                    "application/octet-stream"))
            }
            startActivityForResult(intent, REQUEST_KLE)
        }

        fun loadImported(json: String) = applyLayout(json, null)

        private fun loadBundled(asset: String, names: List<String>) {
            runCatching { applyLayout(assets.open(asset).bufferedReader().use { it.readText() }, names) }
                .onFailure { status = "レイアウトを読み込めません: ${it.message}" }
        }

        private fun applyLayout(json: String, names: List<String>?) {
            val parsed = KleLayout.parse(json)
            require(names == null || names.size == parsed.keys.size)
            kleLayout = parsed
            currentKleJson = json
            baseBindings = if (names != null) BundledBindings.into(JSONObject(), names)
                else JSONObject().also { mappings ->
                    parsed.keys.forEach { key -> KeyBinding.fromKleLabels(key)?.let { mappings.put(key.index.toString(), it.name) } }
                }
            layerBindings = JSONObject()
            persistLayout(null)
        }

        private fun persistLayout(name: String?) {
            settings.clearAutoKeyScalePx()
            updateSettings()
            preferences.edit().putString("kle_json", currentKleJson)
                .putString("overrides", baseBindings.toString())
                .putString("layer_overrides", layerBindings.toString())
                .putString("selected_saved_layout_name", name).apply()
            activeLayer = 0
            activeLayerKeyIndex = null
            oneShotPrevious = null
            oneShotPreviousKey = null
            momentaryPrevious = null
            momentaryPreviousKey = null
            momentaryTarget = null
            modifiers = 0
            viewState = UiViewState(1f, 0f, 0f)
            viewRevision++
            updateLayout()
            updateLayoutChoices()
        }

        private fun updateLayoutChoices() {
            layoutChoices = listOf("標準トラックパッド", "標準キーボード（TKL）") +
                savedLayouts.all().map { "保存: ${it.name}" }
        }

        override fun beginEditing() {
            baseSnapshot = baseBindings.toString()
            layerSnapshot = layerBindings.toString()
            editLayer = activeLayer
            editing = true
            updateLayout()
        }

        override fun finishEditing(save: Boolean) {
            if (save) preferences.edit().putString("overrides", baseBindings.toString())
                .putString("layer_overrides", layerBindings.toString()).apply()
            else {
                baseBindings = JSONObject(baseSnapshot)
                layerBindings = JSONObject(layerSnapshot)
            }
            editing = false
            updateLayout()
        }

        override fun setBinding(keyIndex: Int, name: String): Boolean {
            val key = kleLayout.keys.getOrNull(keyIndex) ?: return false
            if (key.ghost) return false
            val mappings = if (editLayer == 0) baseBindings else
                (layerBindings.optJSONObject(editLayer.toString()) ?: JSONObject().also {
                    layerBindings.put(editLayer.toString(), it)
                })
            when (name) {
                "ベースを継承" -> mappings.remove(keyIndex.toString())
                "自動割り当て" -> {
                    val inferred = KeyBinding.fromKleLabels(key)
                    if (inferred != null) mappings.put(keyIndex.toString(), inferred.name)
                    else { status = "このキーのKLEラベルから割り当てを推定できません"; return false }
                }
                else -> {
                    if (KeyBinding.named(name) == null) { status = "無効なキー割り当て: $name"; return false }
                    mappings.put(keyIndex.toString(), name)
                }
            }
            updateLayout()
            return true
        }

        override fun setToggle(id: String, enabled: Boolean) {
            when (id) {
                "sticky" -> {
                    settings.setStickyModifiers(enabled)
                    if (!enabled) modifiers = 0
                }
                "labels" -> settings.setKleJsonKeyLabels(enabled)
                "pinch" -> settings.setPinchZoom(enabled)
                "drag_keyboard" -> settings.setDragMoveKeyboard(enabled)
                "keep_screen" -> {
                    settings.setKeepScreenOn(enabled)
                    if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                "fullscreen" -> settings.setFullscreenControls(enabled)
                "vibration" -> settings.setTouchVibration(enabled)
                "tap_click" -> settings.setTapToClick(enabled)
                "hold_drag" -> settings.setHoldToDrag(enabled)
                "two_scroll" -> settings.setTwoFingerScroll(enabled)
                "trackpad_zoom" -> settings.setTrackpadPinchZoom(enabled)
                "right_click" -> settings.setTwoFingerRightClick(enabled)
                "tap_drag" -> settings.setTapDrag(enabled)
                "middle_click" -> settings.setThreeFingerMiddleClick(enabled)
                "reverse_scroll" -> settings.setReverseScroll(enabled)
            }
            updateSettings()
            if (id == "keep_screen" || id == "fullscreen") applyDeviceSettings()
            if (id == "labels") updateLayout()
        }

        override fun setSlider(id: String, value: Float) {
            when (id) {
                "pointer_speed" -> settings.setPointerSpeed(value)
                "scroll_speed" -> settings.setScrollSpeed(value)
                "key_pitch" -> settings.setKeyPitchMm(value)
                "send_mode" -> settings.setPointerSendMode(value.roundToInt())
                "orientation" -> {
                    settings.setScreenOrientation(value.roundToInt())
                    requestedOrientation = when (settings.screenOrientation()) {
                        1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        3 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
            updateSettings()
        }

        override fun setLayerColor(layer: Int, color: Int) {
            settings.setLayerBorderColor(layer, color)
            updateSettings()
            updateLayout()
        }

        override fun rememberAutoKeyScaleDp(scale: Float) {
            if (settings.autoKeyScalePx() > 0f) return
            settings.setAutoKeyScalePx(scale * resources.displayMetrics.density)
            autoKeyScaleDp = settings.autoKeyScalePx() / resources.displayMetrics.density
        }

        private fun updateSettings() {
            autoKeyScaleDp = settings.autoKeyScalePx() / resources.displayMetrics.density
            layerColors = (0..3).map(settings::layerBorderColor)
            toggles = listOf(
                UiToggle("sticky", "修飾キーの保持", "Ctrl・Shiftなどを次のキーまで保持", settings.stickyModifiers()),
                UiToggle("labels", "KLE JSONのラベル", "オフなら割り当て名を表示", settings.kleJsonKeyLabels()),
                UiToggle("pinch", "キーボードのピンチ拡大", "2本指で表示倍率を変更", settings.pinchZoom()),
                UiToggle("drag_keyboard", "キーボードをドラッグ移動", "表示位置を調整", settings.dragMoveKeyboard()),
                UiToggle("keep_screen", "画面を常にON", "操作中の自動消灯を防ぐ", settings.keepScreenOn()),
                UiToggle("fullscreen", "全画面表示", "操作画面でシステムバーを隠す", settings.fullscreenControls()),
                UiToggle("vibration", "タッチ時に振動", "キーとクリックで振動", settings.touchVibration()),
                UiToggle("tap_click", "タップでクリック", "トラックパッドの単押し", settings.tapToClick()),
                UiToggle("hold_drag", "長押しドラッグ", "押したままポインターを移動", settings.holdToDrag()),
                UiToggle("two_scroll", "2本指スクロール", "トラックパッドでホイール入力", settings.twoFingerScroll()),
                UiToggle("trackpad_zoom", "トラックパッドのピンチズーム", "Ctrlとホイールを送信", settings.trackpadPinchZoom()),
                UiToggle("right_click", "2本指で右クリック", "2本指タップ", settings.twoFingerRightClick()),
                UiToggle("tap_drag", "ダブルタップでドラッグ", "2回目のタップ後に移動", settings.tapDrag()),
                UiToggle("middle_click", "3本指で中クリック", "3本指タップ", settings.threeFingerMiddleClick()),
                UiToggle("reverse_scroll", "スクロール方向を反転", "ホイール方向を逆にする", settings.reverseScroll())
            )
            sliders = listOf(
                UiSlider("pointer_speed", "ポインター速度", settings.pointerSpeed(), 0.5f, 3f),
                UiSlider("scroll_speed", "スクロール速度", settings.scrollSpeed(), 0.5f, 3f),
                UiSlider("key_pitch", "キーピッチ mm（0は自動）", settings.keyPitchMm(), 0f, 24f),
                UiSlider("send_mode", "カーソル送信間隔（0即時・1 16ms・2 32ms）", settings.pointerSendMode().toFloat(), 0f, 2f),
                UiSlider("orientation", "画面の向き（0自動・1縦・2横・3逆横）", settings.screenOrientation().toFloat(), 0f, 3f)
            )
        }

        private fun bindingFor(key: KleLayout.Key): KeyBinding? =
            bindingFor(key, if (editing) editLayer else activeLayer)

        private fun bindingFor(key: KleLayout.Key, layer: Int): KeyBinding? {
            val index = key.index.toString()
            val layerName = if (layer == 0) "" else
                layerBindings.optJSONObject(layer.toString())?.optString(index, "") ?: ""
            return KeyBinding.named(layerName) ?: KeyBinding.named(baseBindings.optString(index, ""))
        }

        private fun updateLayout() {
            val shownLayer = if (editing) editLayer else activeLayer
            layout = UiLayout(kleLayout.name, kleLayout.keys.map { key ->
                val binding = bindingFor(key)
                UiKey(key.index, key.x, key.y, key.w, key.h,
                    key.x2, key.y2, key.w2, key.h2, key.rx, key.ry, key.rotation,
                    key.displayLabel(), key.labels.toList(),
                    if (settings.kleJsonKeyLabels() && key.labels.any { it.isNotEmpty() }) ""
                    else binding?.name.orEmpty(), key.color, key.ghost,
                    (!editing && key.index == activeLayerKeyIndex) ||
                        (!editing && binding != null && binding.modifier != 0 && binding.code == 0 &&
                            modifiers and binding.modifier != 0),
                    key.decal, shownLayer != 0 && layerBindings.optJSONObject(shownLayer.toString())?.has(key.index.toString()) == true,
                    binding?.layerAction == LayerAction.MOMENTARY,
                    if (binding == null && !key.ghost) 0xFFE87C73.toInt() else settings.layerBorderColor(shownLayer))
            })
        }

        override fun consumer(usage: Int) = hid.consumer(usage)
        override fun mouseMove(x: Int, y: Int) = hid.mouseMove(x, y)
        override fun mouseClick(button: Int) { vibrateTouch(); hid.clickMouse(button) }
        override fun mousePress(button: Int) = hid.pressMouse(button)
        override fun mouseRelease(button: Int) = hid.releaseMouse(button)
        override fun scroll(ticks: Int) = hid.scroll(ticks)
        override fun zoom(ticks: Int) = hid.zoom(ticks)
    }
}
