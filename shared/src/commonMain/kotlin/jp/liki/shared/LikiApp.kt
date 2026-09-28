package jp.liki.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** UI contract. Pairing and HID report delivery belong to each platform. */
interface HidPort {
    val status: String
    val connected: Boolean
    fun startPairing()
    fun disconnect()
    fun key(modifiers: Int, usage: Int)
    fun mouseMove(x: Int, y: Int)
    fun mouseClick(button: Int)
}

@Composable
fun LikiApp(port: HidPort, modifier: Modifier = Modifier) {
    var page by remember { mutableIntStateOf(0) }
    MaterialTheme {
        Surface(modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Liki", style = MaterialTheme.typography.headlineLarge)
                Text(port.status)
                if (!port.connected) Button(onClick = port::startPairing) { Text("ペアリング待機") }
                else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { page = 0 }) { Text("キーボード") }
                        OutlinedButton(onClick = { page = 1 }) { Text("トラックパッド") }
                        OutlinedButton(onClick = port::disconnect) { Text("切断") }
                    }
                    if (page == 0) Keyboard(port, Modifier.weight(1f))
                    else Trackpad(port, Modifier.weight(1f))
                }
            }
        }
    }
}

private val rows = listOf(
    listOf("Esc" to 0x29, "1" to 0x1e, "2" to 0x1f, "3" to 0x20, "4" to 0x21, "5" to 0x22, "6" to 0x23, "7" to 0x24, "8" to 0x25, "9" to 0x26, "0" to 0x27),
    listOf("Tab" to 0x2b, "Q" to 0x14, "W" to 0x1a, "E" to 0x08, "R" to 0x15, "T" to 0x17, "Y" to 0x1c, "U" to 0x18, "I" to 0x0c, "O" to 0x12, "P" to 0x13),
    listOf("A" to 0x04, "S" to 0x16, "D" to 0x07, "F" to 0x09, "G" to 0x0a, "H" to 0x0b, "J" to 0x0d, "K" to 0x0e, "L" to 0x0f, "Enter" to 0x28),
    listOf("Z" to 0x1d, "X" to 0x1b, "C" to 0x06, "V" to 0x19, "B" to 0x05, "N" to 0x11, "M" to 0x10, "⌫" to 0x2a),
    listOf("Space" to 0x2c, "←" to 0x50, "↓" to 0x51, "↑" to 0x52, "→" to 0x4f)
)

@Composable
private fun Keyboard(port: HidPort, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { keys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                keys.forEach { (label, usage) ->
                    OutlinedButton(onClick = { port.key(0, usage) }, modifier = Modifier.weight(if (label == "Space") 4f else 1f)) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Trackpad(port: HidPort, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
                .pointerInput(port) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        port.mouseMove(drag.x.roundToInt(), drag.y.roundToInt())
                    }
                },
            contentAlignment = Alignment.Center
        ) { Text("トラックパッド") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { port.mouseClick(1) }, modifier = Modifier.weight(1f)) { Text("左クリック") }
            OutlinedButton(onClick = { port.mouseClick(2) }, modifier = Modifier.weight(1f)) { Text("右クリック") }
        }
    }
}
