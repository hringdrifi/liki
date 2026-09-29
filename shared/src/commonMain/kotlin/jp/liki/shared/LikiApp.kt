package jp.liki.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

data class UiKey(
    val index: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val x2: Float,
    val y2: Float,
    val width2: Float,
    val height2: Float,
    val rotationX: Float,
    val rotationY: Float,
    val rotation: Float,
    val label: String,
    val labels: List<String>,
    val binding: String,
    val color: Int,
    val ghost: Boolean,
    val active: Boolean,
    val decal: Boolean,
    val overridden: Boolean,
    val momentary: Boolean,
    val borderColor: Int
)

data class UiLayout(val name: String, val keys: List<UiKey>)
data class UiViewState(val zoom: Float, val panX: Float, val panY: Float)
data class UiToggle(val id: String, val title: String, val detail: String, val enabled: Boolean)
data class UiSlider(val id: String, val title: String, val value: Float, val minimum: Float, val maximum: Float)
data class UiBindingChoice(val name: String, val category: String)

/** UI contract. Pairing and HID report delivery belong to each platform. */
interface HidPort {
    val status: String
    val connected: Boolean
    val connecting: Boolean
    val devices: List<String>
    val layout: UiLayout
    val viewState: UiViewState
    val viewRevision: Int
    val activeLayer: Int
    val oneShotActive: Boolean
    val editLayer: Int
    val editing: Boolean
    var settingsVisible: Boolean
    var backHandler: (() -> Boolean)?
    val layoutChoices: List<String>
    val bindingChoices: List<UiBindingChoice>
    val toggles: List<UiToggle>
    val sliders: List<UiSlider>
    val layerColors: List<Int>
    val autoKeyScaleDp: Float
    val keyPitchDpPerMm: Float
    val privacyText: String
    fun startPairing()
    fun refreshDevices()
    fun connect(index: Int)
    fun cancelConnection()
    fun disconnect()
    fun openAdvanced()
    fun key(modifiers: Int, usage: Int)
    fun tapKey(index: Int)
    fun tapKeyAtLayer(index: Int, layer: Int)
    fun momentaryDown(index: Int)
    fun momentaryUp()
    fun updateViewState(state: UiViewState)
    fun resetView()
    fun selectLayer(layer: Int)
    fun selectEditLayer(layer: Int)
    fun selectLayout(index: Int)
    fun saveLayout(name: String)
    fun deleteLayout(index: Int)
    fun importLayout()
    fun beginEditing()
    fun finishEditing(save: Boolean)
    fun setBinding(keyIndex: Int, name: String): Boolean
    fun setToggle(id: String, enabled: Boolean)
    fun setSlider(id: String, value: Float)
    fun setLayerColor(layer: Int, color: Int)
    fun rememberAutoKeyScaleDp(scale: Float)
    fun consumer(usage: Int)
    fun mouseMove(x: Int, y: Int)
    fun mouseClick(button: Int)
    fun mousePress(button: Int)
    fun mouseRelease(button: Int)
    fun scroll(ticks: Int)
    fun zoom(ticks: Int)
}

@Composable
fun LikiApp(port: HidPort, modifier: Modifier = Modifier) {
    var page by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var layoutOpen by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableIntStateOf(-1) }
    var overwriteOpen by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var bindingKey by remember { mutableIntStateOf(-1) }
    var bindingCategory by remember { mutableStateOf<String?>(null) }
    var customBinding by remember { mutableStateOf("") }
    var bindingError by remember { mutableStateOf(false) }
    var selectedModifiers by remember { mutableStateOf(emptyList<String>()) }
    var privacyOpen by remember { mutableStateOf(false) }
    var colorLayer by remember { mutableIntStateOf(-1) }
    var colorInput by remember { mutableStateOf("") }
    var selectedDevice by remember { mutableIntStateOf(0) }
    var deviceMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(port.connected) {
        if (!port.connected) {
            page = 0
            menuOpen = false
            layoutOpen = false
        }
    }
    SideEffect {
        port.settingsVisible = port.connected && page == 2
        port.backHandler = {
            when {
                bindingKey >= 0 -> { bindingKey = -1; true }
                colorLayer >= 0 -> { colorLayer = -1; true }
                privacyOpen -> { privacyOpen = false; true }
                overwriteOpen -> { overwriteOpen = false; true }
                deleteCandidate >= 0 -> { deleteCandidate = -1; true }
                saveOpen -> { saveOpen = false; true }
                deleteOpen -> { deleteOpen = false; true }
                layoutOpen -> { layoutOpen = false; true }
                deviceMenuOpen -> { deviceMenuOpen = false; true }
                menuOpen -> { menuOpen = false; true }
                port.editing -> { port.finishEditing(false); true }
                page != 0 -> { page = 0; true }
                else -> false
            }
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFF10151C), surface = Color(0xFF10151C),
        surfaceVariant = Color(0xFF1F2834), primary = Color(0xFF80CBC4))) {
        Surface(modifier.fillMaxSize()) {
            if (!port.connected) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Liki", fontSize = 36.sp)
                    Text("Pocket HID", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                    Text("スマホをPCのキーボード・トラックパッドに", fontSize = 14.sp)
                    Text(port.status, Modifier.padding(top = 18.dp, bottom = 16.dp), fontSize = 15.sp)
                    Text("1. PCとペアリング", color = MaterialTheme.colorScheme.primary)
                    Text("下のボタンを押した後、PCのBluetooth設定からこのスマホを追加します。", fontSize = 14.sp)
                    Button(onClick = port::startPairing, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                        Text("ペアリング待機（5分間公開）")
                    }
                    Text("2. 接続先を選択", Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { deviceMenuOpen = true }, Modifier.fillMaxWidth()) {
                                Text(port.devices.getOrNull(selectedDevice) ?: "ペアリング済みPCなし")
                            }
                            DropdownMenu(expanded = deviceMenuOpen,
                                onDismissRequest = { deviceMenuOpen = false }) {
                                port.devices.forEachIndexed { index, name ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = {
                                        selectedDevice = index; deviceMenuOpen = false
                                    })
                                }
                            }
                        }
                        TextButton(onClick = port::refreshDevices) { Text("更新") }
                    }
                    Button(onClick = {
                        if (port.connecting) port.cancelConnection()
                        else if (port.devices.isNotEmpty()) port.connect(selectedDevice.coerceIn(port.devices.indices))
                    }, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                        Text(if (port.connecting) "接続をキャンセル" else "選択したPCに接続")
                    }
                    Text("接続すると操作画面へ切り替わります。PCから自動接続される場合もあります。",
                        Modifier.padding(top = 8.dp), fontSize = 13.sp)
                    TextButton(onClick = { privacyOpen = true }) { Text("プライバシーポリシー") }
                    TextButton(onClick = port::openAdvanced) { Text("従来画面を開く") }
                }
            } else {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    if (page == 0 && port.editing) {
                        Column(Modifier.fillMaxSize().padding(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                (0..3).forEach { layer ->
                                    if (port.editLayer == layer)
                                        Button(onClick = { port.selectEditLayer(layer) }, Modifier.weight(1f)) {
                                            Text("レイヤー $layer", fontSize = 11.sp)
                                        }
                                    else OutlinedButton(onClick = { port.selectEditLayer(layer) }, Modifier.weight(1f)) {
                                        Text("レイヤー $layer", fontSize = 11.sp)
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(onClick = { port.finishEditing(false) }, Modifier.weight(1f)) { Text("キャンセル") }
                                Button(onClick = { port.finishEditing(true) }, Modifier.weight(1f)) { Text("保存") }
                            }
                            KleKeyboardCanvas(port, Modifier.weight(1f).fillMaxWidth(), onEditKey = {
                                bindingKey = it; bindingCategory = null; bindingError = false
                                selectedModifiers = emptyList()
                            })
                        }
                    } else if (page == 0) KleKeyboardCanvas(port,
                        Modifier.fillMaxSize().padding(8.dp), onEditKey = {})
                    else if (page == 1) Trackpad(port, Modifier.fillMaxSize().padding(8.dp))
                    else Column(Modifier.fillMaxSize().padding(16.dp)) {
                        TextButton(onClick = { page = 0 }) { Text("‹  操作画面に戻る") }
                        Settings(port, Modifier.weight(1f), onPrivacy = { privacyOpen = true },
                            onColor = { layer ->
                            colorLayer = layer
                            colorInput = "#" + (port.layerColors[layer] and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
                        })
                    }
                    if (page == 0 && !port.editing) {
                        Surface(Modifier.align(Alignment.TopStart).padding(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)) {
                            Text("L${port.activeLayer}${if (port.oneShotActive) "・1回" else ""}",
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (!port.editing && page != 2) {
                        Box(Modifier.align(Alignment.CenterEnd).width(24.dp).height(108.dp)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: break
                                        val delta = change.position - down.position
                                        if (delta.x < -42f * density &&
                                            abs(delta.x) > abs(delta.y) * 1.25f) {
                                            menuOpen = true
                                            change.consume()
                                            break
                                        }
                                        if (!change.pressed) break
                                    }
                                }
                            }) {
                            Box(Modifier.align(Alignment.CenterEnd).width(3.dp).height(48.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                        }
                    }
                    if (menuOpen) {
                        Box(Modifier.fillMaxSize().background(Color(0x96000000)).clickable { menuOpen = false })
                        Surface(Modifier.align(Alignment.CenterEnd).padding(12.dp).width(216.dp)
                            .heightIn(max = 600.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(18.dp), shadowElevation = 12.dp) {
                            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                                Text("Liki", style = MaterialTheme.typography.headlineSmall)
                                Text(port.status, style = MaterialTheme.typography.bodySmall)
                                Text(port.layout.name, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                                DropdownMenuItem(text = { Text("キー割り当てを編集") }, onClick = {
                                    menuOpen = false; page = 0; port.beginEditing()
                                })
                                DropdownMenuItem(text = { Text("レイアウトを選択") }, onClick = {
                                    menuOpen = false; layoutOpen = true
                                })
                                DropdownMenuItem(text = { Text("表示をリセット") }, onClick = {
                                    menuOpen = false; port.resetView()
                                })
                                DropdownMenuItem(text = { Text("トラックパッド") }, onClick = {
                                    menuOpen = false; page = 1
                                })
                                DropdownMenuItem(text = { Text("キーボード") }, onClick = {
                                    menuOpen = false; page = 0
                                })
                                DropdownMenuItem(text = { Text("設定") }, onClick = {
                                    menuOpen = false; page = 2
                                })
                                DropdownMenuItem(text = { Text("プライバシーポリシー") }, onClick = {
                                    menuOpen = false; privacyOpen = true
                                })
                                DropdownMenuItem(text = { Text("切断") }, onClick = {
                                    menuOpen = false; port.disconnect()
                                })
                            }
                        }
                    }
                }
            }
        }
    }
    if (layoutOpen) AlertDialog(
        onDismissRequest = { layoutOpen = false }, title = { Text("レイアウトを選択") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                port.layoutChoices.forEachIndexed { index, name ->
                    TextButton(onClick = { port.selectLayout(index); layoutOpen = false }) { Text(name) }
                }
                TextButton(onClick = { layoutOpen = false; port.importLayout() }) { Text("KLE JSONを読み込む") }
                TextButton(onClick = { layoutOpen = false; saveOpen = true }) { Text("現在のレイアウトを保存") }
                TextButton(onClick = { layoutOpen = false; deleteOpen = true }) { Text("保存したレイアウトを削除") }
            }
        }, confirmButton = { TextButton(onClick = { layoutOpen = false }) { Text("閉じる") } }
    )
    if (saveOpen) AlertDialog(
        onDismissRequest = { saveOpen = false }, title = { Text("レイアウトを保存") },
        text = { TextField(value = saveName, onValueChange = { saveName = it.take(40) }, label = { Text("名前") }) },
        confirmButton = { TextButton(onClick = {
            val name = saveName.trim()
            if (name.isNotEmpty()) {
                if (port.layoutChoices.drop(2).any {
                    it.removePrefix("保存: ").equals(name, ignoreCase = true)
                }) overwriteOpen = true
                else { port.saveLayout(name); saveOpen = false }
            }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { saveOpen = false }) { Text("キャンセル") } }
    )
    if (deleteOpen) AlertDialog(
        onDismissRequest = { deleteOpen = false }, title = { Text("保存したレイアウトを削除") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            port.layoutChoices.drop(2).forEachIndexed { index, name ->
            TextButton(onClick = { deleteCandidate = index + 2; deleteOpen = false }) { Text(name) }
        } } }, confirmButton = { TextButton(onClick = { deleteOpen = false }) { Text("閉じる") } }
    )
    if (overwriteOpen) AlertDialog(onDismissRequest = { overwriteOpen = false },
        title = { Text("「${saveName.trim()}」を上書きしますか？") },
        confirmButton = { TextButton(onClick = {
            port.saveLayout(saveName.trim()); overwriteOpen = false; saveOpen = false
        }) { Text("上書き") } },
        dismissButton = { TextButton(onClick = { overwriteOpen = false }) { Text("キャンセル") } })
    if (deleteCandidate >= 0) AlertDialog(onDismissRequest = { deleteCandidate = -1 },
        title = { Text("「${port.layoutChoices.getOrNull(deleteCandidate)?.removePrefix("保存: ") ?: ""}」を削除しますか？") },
        text = { Text("現在の画面はそのまま残ります。") },
        confirmButton = { TextButton(onClick = {
            port.deleteLayout(deleteCandidate); deleteCandidate = -1
        }) { Text("削除") } },
        dismissButton = { TextButton(onClick = { deleteCandidate = -1 }) { Text("キャンセル") } })
    if (bindingKey >= 0) AlertDialog(
        onDismissRequest = { bindingKey = -1 }, title = { Text("L${port.editLayer}・キー割り当て") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (bindingCategory == null) {
                if (port.editLayer == 0) TextButton(onClick = {
                    if (port.setBinding(bindingKey, "自動割り当て")) bindingKey = -1 else bindingError = true
                }) { Text("KLEラベルから自動割り当て") }
                else TextButton(onClick = {
                    if (port.setBinding(bindingKey, "ベースを継承")) bindingKey = -1
                }) { Text("ベースを継承") }
                val categories = listOf("文字・数字", "記号", "基本キー", "修飾キー付き",
                    "メディアキー", "ファンクションキー", "移動・編集", "テンキー",
                    "修飾キー単体", "ショートカット", "レイヤー", "その他")
                categories.filter { category -> category == "修飾キー付き" ||
                    port.bindingChoices.any { it.category == category } }.forEach { category ->
                    TextButton(onClick = { bindingCategory = category }) { Text(category) }
                }
                TextButton(onClick = { bindingCategory = "式を直接入力" }) { Text("式を直接入力") }
            } else if (bindingCategory == "修飾キー付き") {
                Text("選択中: " + selectedModifiers.joinToString(" + ").ifBlank { "修飾キーを選択" })
                if (selectedModifiers.isNotEmpty()) TextButton(onClick = {
                    selectedModifiers = selectedModifiers.dropLast(1)
                }) { Text("最後の修飾キーを外す") }
                if (selectedModifiers.size < 8) listOf("LCTL", "LSFT", "LALT", "LGUI",
                    "RCTL", "RSFT", "RALT", "RGUI").filterNot { it in selectedModifiers }.forEach { mod ->
                    TextButton(onClick = { selectedModifiers = selectedModifiers + mod }) { Text(mod) }
                }
                if (selectedModifiers.isNotEmpty()) {
                    Text("キーを選択")
                    port.bindingChoices.filter { it.category in listOf("文字・数字", "記号", "基本キー",
                        "ファンクションキー", "移動・編集", "テンキー") }.forEach { choice ->
                        TextButton(onClick = {
                            val expression = selectedModifiers.foldRight(choice.name) { mod, inner -> "$mod($inner)" }
                            if (port.setBinding(bindingKey, expression)) bindingKey = -1 else bindingError = true
                        }) { Text(choice.name) }
                    }
                }
            } else if (bindingCategory == "式を直接入力") {
                Text("例: LCTL(LSFT(A))", style = MaterialTheme.typography.bodySmall)
                TextField(value = customBinding, onValueChange = { customBinding = it.take(80) },
                    label = { Text("修飾キー付き割り当て") })
                TextButton(onClick = {
                    if (port.setBinding(bindingKey, customBinding.trim())) bindingKey = -1 else bindingError = true
                }) { Text("設定") }
            } else {
                port.bindingChoices.filter { it.category == bindingCategory }.forEach { choice ->
                    TextButton(onClick = {
                        if (port.setBinding(bindingKey, choice.name)) bindingKey = -1 else bindingError = true
                    }) { Text(choice.name) }
                }
            }
            if (bindingError) Text(port.status, color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(onClick = {
            if (bindingCategory != null) bindingCategory = null else bindingKey = -1
        }) { Text(if (bindingCategory != null) "戻る" else "閉じる") } }
    )
    if (privacyOpen) AlertDialog(
        onDismissRequest = { privacyOpen = false }, title = { Text("プライバシーポリシー") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(port.privacyText) } },
        confirmButton = { TextButton(onClick = { privacyOpen = false }) { Text("閉じる") } }
    )
    if (colorLayer >= 0) AlertDialog(
        onDismissRequest = { colorLayer = -1 }, title = { Text("レイヤー $colorLayer の枠色") },
        text = { Column {
            listOf("グレー" to 0x657789, "青" to 0x71A9E8, "緑" to 0x80CBC4,
                "黄" to 0xEDB369, "赤" to 0xE87C73, "紫" to 0xBE9CE7).forEach { (name, color) ->
                TextButton(onClick = { port.setLayerColor(colorLayer, color or 0xFF000000.toInt()); colorLayer = -1 }) {
                    Text(name)
                }
            }
            TextField(value = colorInput, onValueChange = { colorInput = it.take(7) }, label = { Text("#RRGGBB") })
        } },
        confirmButton = { TextButton(onClick = {
            val color = colorInput.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)
            if (color != null) { port.setLayerColor(colorLayer, (color or 0xFF000000).toInt()); colorLayer = -1 }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { colorLayer = -1 }) { Text("キャンセル") } }
    )
}

@Composable
private fun Settings(port: HidPort, modifier: Modifier = Modifier,
    onPrivacy: () -> Unit, onColor: (Int) -> Unit) {
    var choice by remember { mutableStateOf<String?>(null) }
    val toggles = port.toggles.associateBy { it.id }
    val sliders = port.sliders.associateBy { it.id }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("設定", style = MaterialTheme.typography.headlineMedium)
        Text("操作方法を切り替えます。変更はすぐに保存されます。",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onPrivacy) { Text("プライバシーポリシー") }
        Text("表示", color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = { choice = "orientation" }) {
            Text("画面の向き: " + listOf("自動", "縦向き", "横向き", "横向き（反対向き）")
                .getOrElse(sliders["orientation"]?.value?.roundToInt() ?: 0) { "自動" })
        }
        listOf("fullscreen", "keep_screen").forEach { id ->
            toggles[id]?.let { setting ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(setting.title)
                        Text(setting.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = setting.enabled, onCheckedChange = { port.setToggle(id, it) })
                }
            }
        }
        Text("操作感", color = MaterialTheme.colorScheme.primary)
        toggles["vibration"]?.let { setting ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(setting.title, Modifier.weight(1f))
                Switch(checked = setting.enabled, onCheckedChange = { port.setToggle(setting.id, it) })
            }
        }
        Text("キーボード", color = MaterialTheme.colorScheme.primary)
        (0..3).forEach { layer ->
            TextButton(onClick = { onColor(layer) }) {
                Text("レイヤー $layer の枠色  #" +
                    (port.layerColors[layer] and 0xFFFFFF).toString(16).padStart(6, '0').uppercase())
            }
        }
        sliders["key_pitch"]?.let { setting ->
            val effective = setting.value.takeIf { it > 0f } ?: (port.autoKeyScaleDp /
                port.keyPitchDpPerMm).takeIf { it > 0f } ?: 6f
            Text("キーピッチ: 約 ${((effective * 10).roundToInt() / 10f)} mm")
            Text("画面に収めるにはメニューの「表示をリセット」を押します。",
                style = MaterialTheme.typography.bodySmall)
            Slider(value = effective.coerceIn(setting.minimum, setting.maximum),
                onValueChange = { port.setSlider(setting.id, it) },
                valueRange = setting.minimum..setting.maximum)
        }
        listOf("labels", "sticky", "pinch", "drag_keyboard").forEach { id ->
            toggles[id]?.let { setting ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(setting.title)
                        Text(setting.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = setting.enabled, onCheckedChange = { port.setToggle(id, it) })
                }
            }
        }
        Text("トラックパッド", color = MaterialTheme.colorScheme.primary)
        sliders["pointer_speed"]?.let { setting ->
            Text("${setting.title}: ${((setting.value * 10).roundToInt() / 10f)}倍")
            Slider(value = setting.value, onValueChange = { port.setSlider(setting.id, it) },
                valueRange = setting.minimum..setting.maximum)
        }
        TextButton(onClick = { choice = "send_mode" }) {
            Text("カーソルの送信間隔: " + listOf("即時", "16ms", "32ms")
                .getOrElse(sliders["send_mode"]?.value?.roundToInt() ?: 2) { "32ms" })
        }
        listOf("tap_click", "tap_drag", "hold_drag", "right_click", "two_scroll",
            "trackpad_zoom", "middle_click").forEach { id ->
            toggles[id]?.let { setting ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(setting.title)
                        Text(setting.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = setting.enabled, onCheckedChange = { port.setToggle(id, it) })
                }
            }
        }
        sliders["scroll_speed"]?.let { setting ->
            Text("${setting.title}: ${((setting.value * 10).roundToInt() / 10f)}倍")
            Slider(value = setting.value, onValueChange = { port.setSlider(setting.id, it) },
                valueRange = setting.minimum..setting.maximum)
        }
        toggles["reverse_scroll"]?.let { setting ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(setting.title, Modifier.weight(1f))
                Switch(checked = setting.enabled, onCheckedChange = { port.setToggle(setting.id, it) })
            }
        }
    }
    if (choice != null) AlertDialog(onDismissRequest = { choice = null },
        title = { Text(if (choice == "orientation") "画面の向き" else "カーソルの送信間隔") },
        text = { Column {
            val options = if (choice == "orientation")
                listOf("自動（端末の設定に従う）", "縦向き", "横向き", "横向き（反対向き）")
            else listOf("即時（毎回送信）", "16ms（送信多め）", "32ms（標準）")
            options.forEachIndexed { index, name ->
                TextButton(onClick = { port.setSlider(choice!!, index.toFloat()); choice = null }) {
                    Text(name)
                }
            }
        } }, confirmButton = { TextButton(onClick = { choice = null }) { Text("キャンセル") } })
}

@Composable
private fun Trackpad(port: HidPort, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
                .trackpadGestures(port),
            contentAlignment = Alignment.Center
        ) { Text("トラックパッド") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { port.mouseClick(1) }, modifier = Modifier.weight(1f)) { Text("左クリック") }
            OutlinedButton(onClick = { port.mouseClick(2) }, modifier = Modifier.weight(1f)) { Text("右クリック") }
        }
    }
}

internal fun Modifier.trackpadGestures(port: HidPort): Modifier = pointerInput(port, port.toggles, port.sliders) {
    fun enabled(id: String) = port.toggles.firstOrNull { it.id == id }?.enabled ?: true
    fun value(id: String) = port.sliders.firstOrNull { it.id == id }?.value ?: 1f
    var lastTapAt = 0L
    var lastTapPosition = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown()
        val startedAt = down.uptimeMillis
        val tapDrag = enabled("tap_drag") && enabled("tap_click") && startedAt - lastTapAt in 1..350 &&
            (down.position - lastTapPosition).getDistance() < 24 * density
        var last = down.position
        var lastCenter = down.position
        var lastSpan = 0f
        var startCenter = down.position
        var startSpan = 0f
        var scrollRemainder = 0f
        var zoomRemainder = 0f
        var maxPointers = 1
        var moved = false
        var dragging = false
        var mode = 0 // 0 undecided, 1 scroll, 2 zoom
        var releasedAt = startedAt
        var remainderX = 0f
        var remainderY = 0f
        try {
            while (true) {
            val holdRemaining = 450L - (releasedAt - startedAt)
            val event = if (enabled("hold_drag") && !moved && !dragging && maxPointers == 1 && holdRemaining > 0)
                withTimeoutOrNull(holdRemaining) { awaitPointerEvent() }
            else awaitPointerEvent()
            if (event == null) {
                port.mousePress(1)
                dragging = true
                continue
            }
            val pressed = event.changes.filter { it.pressed }
            maxPointers = maxOf(maxPointers, pressed.size)
            releasedAt = event.changes.maxOfOrNull { it.uptimeMillis } ?: releasedAt
            when {
                pressed.size >= 2 -> {
                    val center = (pressed[0].position + pressed[1].position) / 2f
                    val span = (pressed[0].position - pressed[1].position).getDistance()
                    if (lastSpan == 0f) {
                        startCenter = center
                        startSpan = span
                    } else {
                        val totalTravel = (center - startCenter).getDistance()
                        val totalSpanChange = abs(span - startSpan)
                        if (totalTravel > 8 * density || totalSpanChange > 8 * density) moved = true
                        if (mode == 0) mode = when {
                            enabled("trackpad_zoom") && totalSpanChange > 12 * density &&
                                totalSpanChange > totalTravel * 1.2f -> 2
                            totalTravel > 8 * density &&
                                (totalTravel > totalSpanChange * .8f || !enabled("trackpad_zoom")) -> 1
                            else -> 0
                        }
                        if (mode == 2) {
                            val amount = (span - lastSpan) / (24 * density) + zoomRemainder
                            val ticks = amount.toInt()
                            zoomRemainder = amount - ticks
                            if (ticks != 0) port.zoom(ticks)
                        } else if (mode == 1) {
                            val reverse = if (enabled("reverse_scroll")) -1f else 1f
                            val amount = (lastCenter.y - center.y) / (18 * density) * value("scroll_speed") * reverse + scrollRemainder
                            val ticks = amount.toInt()
                            scrollRemainder = amount - ticks
                            if (ticks != 0 && enabled("two_scroll")) port.scroll(ticks)
                        }
                    }
                    lastCenter = center
                    lastSpan = span
                    if (dragging) { port.mouseRelease(1); dragging = false }
                }
                pressed.size == 1 && maxPointers == 1 -> {
                    val current = pressed[0].position
                    val delta = current - last
                    if ((current - down.position).getDistance() > 8 * density) {
                        moved = true
                        if (tapDrag && !dragging) { port.mousePress(1); dragging = true }
                    }
                    if (delta != Offset.Zero && (!tapDrag || moved || dragging)) {
                        val dx = delta.x * 1.45f * value("pointer_speed") + remainderX
                        val dy = delta.y * 1.45f * value("pointer_speed") + remainderY
                        val ix = dx.toInt(); val iy = dy.toInt()
                        remainderX = dx - ix; remainderY = dy - iy
                        if (ix != 0 || iy != 0) port.mouseMove(ix, iy)
                    }
                    last = current
                }
            }
            event.changes.forEach { it.consume() }
                if (pressed.isEmpty()) break
            }
        } finally {
            if (dragging) port.mouseRelease(1)
        }
        if (!dragging && !moved && releasedAt - startedAt < 450) {
            when (maxPointers) {
                1 -> if (enabled("tap_click")) { port.mouseClick(1); lastTapAt = releasedAt; lastTapPosition = down.position }
                2 -> if (enabled("right_click")) port.mouseClick(2)
                3 -> if (enabled("middle_click")) port.mouseClick(4)
            }
        }
        if (moved || maxPointers > 1) lastTapAt = 0L
    }
}
