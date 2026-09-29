package jp.liki.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The original KLE geometry and hit testing, drawn with multiplatform Compose Canvas. */
@Composable
internal fun KleKeyboardCanvas(port: HidPort, modifier: Modifier = Modifier, onEditKey: (Int) -> Unit) {
    val keys = port.layout.keys
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()
    var zoom by remember(port.layout.name, port.viewRevision) { mutableFloatStateOf(port.viewState.zoom) }
    var pan by remember(port.layout.name, port.viewRevision) {
        mutableStateOf(Offset(port.viewState.panX, port.viewState.panY))
    }
    val corners = keys.flatMap { key -> listOf(
        rotatedCorner(key, 0f, 0f), rotatedCorner(key, key.width, 0f),
        rotatedCorner(key, 0f, key.height), rotatedCorner(key, key.width, key.height),
        rotatedCorner(key, key.x2, key.y2), rotatedCorner(key, key.x2 + key.width2, key.y2),
        rotatedCorner(key, key.x2, key.y2 + key.height2),
        rotatedCorner(key, key.x2 + key.width2, key.y2 + key.height2)) }
    val minX = corners.minOfOrNull { it.x } ?: 0f
    val minY = corners.minOfOrNull { it.y } ?: 0f
    val maxX = corners.maxOfOrNull { it.x } ?: 1f
    val maxY = corners.maxOfOrNull { it.y } ?: 1f
    val pinchEnabled = port.toggles.firstOrNull { it.id == "pinch" }?.enabled ?: true
    val panEnabled = port.toggles.firstOrNull { it.id == "drag_keyboard" }?.enabled ?: true
    val pitch = port.sliders.firstOrNull { it.id == "key_pitch" }?.value ?: 0f

    BoxWithConstraints(modifier.clipToBounds()) {
        val widthPx = maxWidth.value * density
        val heightPx = maxHeight.value * density
        val fittedScale = minOf((widthPx - 20f * density) / maxOf(1f, maxX - minX),
            (heightPx - 20f * density) / maxOf(1f, maxY - minY))
        if (pitch <= 0f && port.autoKeyScaleDp <= 0f) {
            LaunchedEffect(fittedScale) {
                if (fittedScale > 0f) port.rememberAutoKeyScaleDp(fittedScale / density)
            }
        }
        val baseScale = if (pitch > 0f) pitch * port.keyPitchDpPerMm * density
            else (port.autoKeyScaleDp * density).takeIf { it > 0f } ?: fittedScale
        val scale = baseScale * zoom
        val originX = (widthPx - (maxX - minX) * scale) / 2f - minX * scale + pan.x
        val originY = (heightPx - (maxY - minY) * scale) / 2f - minY * scale + pan.y

        Canvas(Modifier.fillMaxSize().pointerInput(port.viewRevision, baseScale,
            pinchEnabled, panEnabled, port.editing) {
            fun hit(position: Offset): UiKey? {
                val worldX = (position.x - ((widthPx - (maxX - minX) * baseScale * zoom) / 2f -
                    minX * baseScale * zoom + pan.x)) / (baseScale * zoom)
                val worldY = (position.y - ((heightPx - (maxY - minY) * baseScale * zoom) / 2f -
                    minY * baseScale * zoom + pan.y)) / (baseScale * zoom)
                return keys.asReversed().firstOrNull { key ->
                    if (key.decal) return@firstOrNull false
                    val radians = key.rotation * kotlin.math.PI / 180.0
                    val dx = worldX - key.rotationX
                    val dy = worldY - key.rotationY
                    val x = key.rotationX + dx * cos(radians) + dy * sin(radians)
                    val y = key.rotationY - dx * sin(radians) + dy * cos(radians)
                    inside(x.toFloat(), y.toFloat(), key.x, key.y, key.width, key.height) ||
                        inside(x.toFloat(), y.toFloat(), key.x + key.x2, key.y + key.y2,
                            key.width2, key.height2)
                }
            }
            fun saveView() { port.updateViewState(UiViewState(zoom, pan.x, pan.y)) }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val selected = hit(down.position)
                if (selected?.ghost == true && !port.editing) return@awaitEachGesture
                val momentary = selected?.momentary == true && !port.editing
                if (momentary) port.momentaryDown(selected.index)
                val momentaryId = if (momentary) down.id else null
                val chord = mutableMapOf<PointerId, Triple<UiKey, Offset, Int>>()
                var momentaryHeld = momentary
                var last = down.position
                var downPosition = down.position
                var lastSpan = 0f
                var moved = false
                var pinching = false
                var pressedBefore = setOf(down.id)
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (momentary) {
                            event.changes.forEach { change ->
                                if (change.id !in pressedBefore && change.pressed) {
                                    hit(change.position)?.takeIf { !it.ghost &&
                                        port.layout.keys.getOrNull(it.index)?.momentary != true }?.let {
                                        chord[change.id] = Triple(it, change.position, port.activeLayer)
                                    }
                                }
                                chord[change.id]?.let { (key, start, layer) ->
                                    if (!change.pressed) {
                                        chord.remove(change.id)
                                        if ((change.position - start).getDistance() <= 8f * density)
                                            port.tapKeyAtLayer(key.index, layer)
                                    }
                                }
                            }
                            if (momentaryHeld && pressed.none { it.id == momentaryId }) {
                                port.momentaryUp()
                                momentaryHeld = false
                            }
                        } else if (pressed.size >= 2) {
                            pinching = true
                            val span = (pressed[0].position - pressed[1].position).getDistance()
                            if (lastSpan > 0f && pinchEnabled) {
                                val focus = (pressed[0].position + pressed[1].position) / 2f
                                val beforeScale = baseScale * zoom
                                val beforeOriginX = (widthPx - (maxX - minX) * beforeScale) / 2f -
                                    minX * beforeScale + pan.x
                                val beforeOriginY = (heightPx - (maxY - minY) * beforeScale) / 2f -
                                    minY * beforeScale + pan.y
                                val worldX = (focus.x - beforeOriginX) / beforeScale
                                val worldY = (focus.y - beforeOriginY) / beforeScale
                                zoom = (zoom * span / lastSpan).coerceIn(if (pitch > 0f) .25f else 1f, 5f)
                                val afterScale = baseScale * zoom
                                val afterOriginX = (widthPx - (maxX - minX) * afterScale) / 2f -
                                    minX * afterScale + pan.x
                                val afterOriginY = (heightPx - (maxY - minY) * afterScale) / 2f -
                                    minY * afterScale + pan.y
                                pan += Offset(focus.x - (afterOriginX + worldX * afterScale),
                                    focus.y - (afterOriginY + worldY * afterScale))
                                saveView()
                            }
                            lastSpan = span
                        } else if (pressed.size == 1 && !pinching) {
                            val point = pressed[0].position
                            if ((point - downPosition).getDistance() > 8f * density) moved = true
                            if (moved && panEnabled) { pan += point - last; saveView() }
                            last = point
                        }
                        pressedBefore = pressed.map { it.id }.toSet()
                        event.changes.forEach { it.consume() }
                        if (pressed.isEmpty()) {
                            if (!momentary && !moved && !pinching && selected != null) {
                                if (port.editing) onEditKey(selected.index) else port.tapKey(selected.index)
                            }
                            break
                        }
                    }
                } finally { if (momentaryHeld) port.momentaryUp() }
            }
        }) {
            keys.forEach { key -> drawKleKey(key,
                if (port.editing) port.editLayer else port.activeLayer,
                scale, originX, originY, textMeasurer) }
        }
        if (!port.editing) keys.filter { it.ghost && !it.decal }.forEach { key ->
            val center = rotatedCorner(key, key.width / 2f, key.height / 2f)
            Box(Modifier.offset(x = ((originX + (center.x - key.width / 2f) * scale) / density).dp,
                y = ((originY + (center.y - key.height / 2f) * scale) / density).dp)
                .size((key.width * scale / density).dp, (key.height * scale / density).dp)
                .graphicsLayer(rotationZ = key.rotation)
                .trackpadGestures(port))
        }
    }
}

private fun DrawScope.drawKleKey(key: UiKey, layer: Int, scale: Float, originX: Float,
    originY: Float, textMeasurer: TextMeasurer) {
    val pivot = Offset(originX + key.rotationX * scale, originY + key.rotationY * scale)
    rotate(key.rotation, pivot = pivot) {
        val source = Color(key.color)
        val fill = when {
            key.active -> Color(0xFF24736D)
            key.overridden -> Color(0xFF26485B)
            else -> Color(20f / 255f + source.red / 4f,
                25f / 255f + source.green / 4f, 32f / 255f + source.blue / 4f)
        }
        fun rect(x: Float, y: Float, w: Float, h: Float) {
            val topLeft = Offset(originX + (x + .025f) * scale, originY + (y + .025f) * scale)
            val size = Size((w - .05f) * scale, (h - .05f) * scale)
            val radius = androidx.compose.ui.geometry.CornerRadius(.1f * scale)
            drawRoundRect(fill, topLeft, size, radius)
            drawRoundRect(Color(key.borderColor), topLeft, size, radius,
                style = Stroke(width = .018f * scale))
        }
        if (!key.decal) {
            rect(key.x, key.y, key.width, key.height)
            if (key.x2 != 0f || key.y2 != 0f || key.width2 != key.width || key.height2 != key.height)
                rect(key.x + key.x2, key.y + key.y2, key.width2, key.height2)
        }
        fun label(value: String, x: Float, baselineY: Float, size: Float, align: Int,
            color: Color = Color(0xFFEBF2F7)) {
            val textSizePx = size * scale
            val style = TextStyle(color = color,
                fontSize = (textSizePx / (density * fontScale)).sp, fontWeight = FontWeight.Bold)
            val layout = textMeasurer.measure(value, style = style)
            val drawX = originX + x * scale - when (align) {
                0 -> 0f; 2 -> layout.size.width.toFloat(); else -> layout.size.width / 2f
            }
            drawText(layout, topLeft = Offset(drawX, originY + baselineY * scale - layout.size.height))
        }
        if (key.binding.isBlank()) {
            key.labels.take(9).forEachIndexed { index, value ->
                if (value.isBlank()) return@forEachIndexed
                val textSize = minOf(.25f, maxOf(.14f,
                    (key.width - .18f) / maxOf(2f, value.length * .54f)))
                val col = index % 3
                val row = index / 3
                val x = when (col) { 0 -> key.x + .11f; 2 -> key.x + key.width - .11f
                    else -> key.x + key.width / 2f }
                val y = when (row) { 0 -> key.y + .3f; 1 -> key.y + key.height / 2f + .08f
                    else -> key.y + key.height - .1f }
                label(value, x, y, textSize, col)
            }
        } else if (!key.ghost) {
            val size = minOf(.28f, maxOf(.12f,
                (key.width - .14f) / maxOf(2f, key.binding.length * .55f)))
            label(key.binding, key.x + key.width / 2f,
                key.y + key.height / 2f + .08f, size, 1)
            if (layer != 0 && !key.overridden) label("継承", key.x + key.width / 2f,
                key.y + key.height - .1f, .13f, 1, Color(0xFFA3B3C0))
        }
    }
}

private fun rotatedCorner(key: UiKey, localX: Float, localY: Float): Offset {
    val x = key.x + localX - key.rotationX
    val y = key.y + localY - key.rotationY
    val radians = key.rotation * kotlin.math.PI / 180.0
    return Offset((key.rotationX + x * cos(radians) - y * sin(radians)).toFloat(),
        (key.rotationY + x * sin(radians) + y * cos(radians)).toFloat())
}

private fun inside(x: Float, y: Float, left: Float, top: Float, width: Float, height: Float) =
    x >= left && x < left + width && y >= top && y < top + height
