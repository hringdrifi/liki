package jp.liki.pockethid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal class KleKeyboardView(
    context: Context,
    hid: HidController,
    private val settings: AppSettings,
    private val listener: Listener
) : View(context) {
    data class ViewState(val zoom: Float, val panX: Float, val panY: Float)

    interface Listener {
        fun bindingFor(key: KleLayout.Key): KeyBinding?
        fun hasLayerOverride(key: KleLayout.Key): Boolean
        fun isLayerKeyActive(key: KleLayout.Key): Boolean
        fun isEditing(): Boolean
        fun activeLayer(): Int
        fun onKeyTap(key: KleLayout.Key, layer: Int)
        fun onMomentaryDown(key: KleLayout.Key)
        fun onMomentaryUp()
    }

    private val trackpadGesture = TrackpadGesture(this, hid, settings)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var keyboardLayout: KleLayout? = null
    private var minX = 0f; private var minY = 0f; private var maxX = 0f; private var maxY = 0f
    private var zoom = 1f; private var panX = 0f; private var panY = 0f
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var pinchSpan = 0f
    private var moved = false; private var pinching = false
    private var trackpadTouch = false
    private var selected: KleLayout.Key? = null
    private var momentaryGesture = false
    private var momentaryPointerId = -1
    private data class ChordTouch(val key: KleLayout.Key, val layer: Int,
        val downX: Float, val downY: Float, var moved: Boolean = false)
    private val chordTouches = mutableMapOf<Int, ChordTouch>()
    private var activeModifiers = 0
    private var activeLayer = 0
    init {
        setBackgroundColor(AppColors.BACKGROUND)
        contentDescription = "KLEキーボード。通常はタップで入力、設定モードではタップで割り当て"
    }

    fun setLayout(layout: KleLayout) {
        keyboardLayout = layout
        zoom = 1f; panX = 0f; panY = 0f
        minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY
        maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY
        for (key in layout.keys) {
            addBounds(key, 0f, 0f); addBounds(key, key.w, 0f)
            addBounds(key, 0f, key.h); addBounds(key, key.w, key.h)
            addBounds(key, key.x2, key.y2)
            addBounds(key, key.x2 + key.w2, key.y2)
            addBounds(key, key.x2, key.y2 + key.h2)
            addBounds(key, key.x2 + key.w2, key.y2 + key.h2)
        }
        invalidate()
    }

    fun setActiveModifiers(modifiers: Int) { activeModifiers = modifiers; invalidate() }
    fun setLayer(layer: Int) { activeLayer = layer; invalidate() }
    fun resetZoom() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }
    fun viewState(): ViewState = ViewState(zoom, panX, panY)
    fun restoreViewState(state: ViewState) {
        zoom = state.zoom
        panX = state.panX
        panY = state.panY
        invalidate()
    }
    fun currentPitchMm(): Float = scale() * 25.4f / horizontalDpi()

    private fun horizontalDpi(): Float = resources.displayMetrics.xdpi.takeIf { it > 0 } ?: density * 160f

    private fun addBounds(key: KleLayout.Key, localX: Float, localY: Float) {
        val x = key.x + localX; val y = key.y + localY
        val angle = Math.toRadians(key.rotation.toDouble())
        val dx = x - key.rx; val dy = y - key.ry
        val resultX = key.rx + dx * cos(angle).toFloat() - dy * sin(angle).toFloat()
        val resultY = key.ry + dx * sin(angle).toFloat() + dy * cos(angle).toFloat()
        minX = minOf(minX, resultX); maxX = maxOf(maxX, resultX)
        minY = minOf(minY, resultY); maxY = maxOf(maxY, resultY)
    }

    private fun baseScale(): Float {
        if (keyboardLayout == null || width == 0 || height == 0) return 1f
        val pitchMm = settings.keyPitchMm()
        if (pitchMm > 0) return pitchMm * horizontalDpi() / 25.4f
        val savedScale = settings.autoKeyScalePx()
        if (savedScale > 0f) return savedScale
        val fittedScale = minOf((width - 20 * density) / maxOf(1f, maxX - minX),
            (height - 20 * density) / maxOf(1f, maxY - minY))
        settings.setAutoKeyScalePx(fittedScale)
        return fittedScale
    }
    private fun scale(): Float = baseScale() * zoom
    private fun originX(): Float = (width - (maxX - minX) * scale()) / 2 - minX * scale() + panX
    private fun originY(): Float = (height - (maxY - minY) * scale()) / 2 - minY * scale() + panY

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = keyboardLayout ?: return
        canvas.save()
        canvas.translate(originX(), originY())
        canvas.scale(scale(), scale())
        for (key in layout.keys) drawKey(canvas, key)
        canvas.restore()
    }

    private fun drawKey(canvas: Canvas, key: KleLayout.Key) {
        canvas.save()
        canvas.rotate(key.rotation, key.rx, key.ry)
        val binding = listener.bindingFor(key)
        val overridden = listener.hasLayerOverride(key)
        val active = !key.ghost && (listener.isLayerKeyActive(key) ||
            binding != null && binding.code == 0 && binding.modifier != 0 &&
                activeModifiers and binding.modifier != 0)
        paint.style = Paint.Style.FILL
        paint.color = when {
            active -> Color.rgb(36, 115, 109)
            activeLayer != 0 && overridden && !key.ghost -> Color.rgb(38, 72, 91)
            else -> darkKeyColor(key.color)
        }
        if (!key.decal) {
            canvas.drawRoundRect(key.x + .025f, key.y + .025f, key.x + key.w - .025f,
                key.y + key.h - .025f, .1f, .1f, paint)
            if (key.x2 != 0f || key.y2 != 0f || key.w2 != key.w || key.h2 != key.h)
                canvas.drawRoundRect(key.x + key.x2 + .025f, key.y + key.y2 + .025f,
                    key.x + key.x2 + key.w2 - .025f, key.y + key.y2 + key.h2 - .025f,
                    .1f, .1f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = .018f
            paint.color = settings.layerBorderColor(activeLayer)
            canvas.drawRoundRect(key.x + .025f, key.y + .025f, key.x + key.w - .025f,
                key.y + key.h - .025f, .1f, .1f, paint)
            if (key.x2 != 0f || key.y2 != 0f || key.w2 != key.w || key.h2 != key.h)
                canvas.drawRoundRect(key.x + key.x2 + .025f, key.y + key.y2 + .025f,
                    key.x + key.x2 + key.w2 - .025f, key.y + key.y2 + key.h2 - .025f,
                    .1f, .1f, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = AppColors.TEXT
        paint.typeface = Typeface.DEFAULT_BOLD
        val showBinding = !key.ghost &&
            (!settings.kleJsonKeyLabels() || key.labels.all { it.isEmpty() })
        for (index in 0 until if (showBinding) 0 else 9) {
            val label = key.labels[index]
            if (label.isEmpty()) continue
            val size = minOf(.25f, maxOf(.14f,
                (key.w - .18f) / maxOf(2f, label.length * .54f)))
            val col = index % 3; val row = index / 3
            paint.textAlign = when (col) { 0 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            val textX = when (col) { 0 -> key.x + .11f; 2 -> key.x + key.w - .11f; else -> key.x + key.w / 2 }
            val textY = when (row) { 0 -> key.y + .3f; 1 -> key.y + key.h / 2 + .08f; else -> key.y + key.h - .1f }
            drawText(canvas, label, textX, textY, size)
        }
        if (showBinding && binding != null) {
            paint.textAlign = Paint.Align.CENTER
            drawText(canvas, binding.name, key.x + key.w / 2,
                key.y + key.h / 2 + .08f,
                minOf(.28f, maxOf(.12f, (key.w - .14f) / maxOf(2f, binding.name.length * .55f))))
            if (activeLayer != 0 && !overridden) {
                paint.color = AppColors.MUTED
                drawText(canvas, "継承", key.x + key.w / 2, key.y + key.h - .1f, .13f)
            }
        }
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, value: String, x: Float, y: Float, unitSize: Float) {
        val pixelsPerUnit = scale()
        canvas.save()
        canvas.scale(1 / pixelsPerUnit, 1 / pixelsPerUnit)
        paint.textSize = unitSize * pixelsPerUnit
        canvas.drawText(value, x * pixelsPerUnit, y * pixelsPerUnit, paint)
        canvas.restore()
    }

    private fun darkKeyColor(color: Int): Int = Color.rgb(
        20 + Color.red(color) / 4, 25 + Color.green(color) / 4, 32 + Color.blue(color) / 4)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val layout = keyboardLayout ?: return false
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.x; lastX = downX; downY = event.y; lastY = downY
            moved = false; pinching = false
            selected = hit(layout, downX, downY)
            trackpadTouch = selected?.ghost == true && !listener.isEditing()
            if (trackpadTouch) { trackpadGesture.onTouch(event); return true }
            if (!listener.isEditing() &&
                selected?.let { listener.bindingFor(it)?.layerAction == LayerAction.MOMENTARY } == true) {
                momentaryGesture = true
                momentaryPointerId = event.getPointerId(0)
                listener.onMomentaryDown(requireNotNull(selected))
                if (settings.touchVibration()) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            return true
        }
        if (momentaryGesture) return handleMomentaryTouch(event, layout)
        if (trackpadTouch) {
            trackpadGesture.onTouch(event)
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) trackpadTouch = false
            return true
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 2) {
            pinching = true
            pinchSpan = span(event)
            return true
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (event.pointerCount >= 2) {
                val newSpan = span(event)
                if (settings.pinchZoom() && pinchSpan > 0) zoomAt(newSpan / pinchSpan,
                    (event.getX(0) + event.getX(1)) / 2, (event.getY(0) + event.getY(1)) / 2)
                pinchSpan = newSpan
            } else if (!pinching) {
                val x = event.x; val y = event.y
                if (hypot(x - downX, y - downY) > 8 * density) {
                    moved = true
                }
                if (moved && settings.dragMoveKeyboard()) {
                    panX += x - lastX; panY += y - lastY; invalidate()
                }
                lastX = x; lastY = y
            }
            return true
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            val key = selected
            if (action == MotionEvent.ACTION_UP && !moved && !pinching && key != null) {
                if (settings.touchVibration()) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                listener.onKeyTap(key, listener.activeLayer())
            }
            selected = null
            return true
        }
        return true
    }

    private fun handleMomentaryTouch(event: MotionEvent, layout: KleLayout): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                val at = event.actionIndex
                val key = hit(layout, event.getX(at), event.getY(at))
                if (key != null && !key.ghost &&
                    listener.bindingFor(key)?.layerAction != LayerAction.MOMENTARY) {
                    chordTouches[event.getPointerId(at)] = ChordTouch(key, listener.activeLayer(),
                        event.getX(at), event.getY(at))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for ((id, touch) in chordTouches) {
                    val at = event.findPointerIndex(id)
                    if (at >= 0 && hypot(event.getX(at) - touch.downX,
                            event.getY(at) - touch.downY) > 8 * density) touch.moved = true
                }
                val at = event.findPointerIndex(momentaryPointerId)
                if (at >= 0 && hypot(event.getX(at) - downX,
                        event.getY(at) - downY) > 8 * density) {
                    moved = true
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == momentaryPointerId) releaseMomentary()
                else chordTouches.remove(id)?.let { touch ->
                    if (!touch.moved) {
                        if (settings.touchVibration()) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        listener.onKeyTap(touch.key, touch.layer)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    releaseMomentary()
                    chordTouches.clear()
                    selected = null
                    momentaryGesture = false
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseMomentary()
                chordTouches.clear()
                selected = null
                momentaryGesture = false
            }
        }
        return true
    }

    private fun releaseMomentary() {
        if (momentaryPointerId >= 0) {
            momentaryPointerId = -1
            listener.onMomentaryUp()
        }
    }

    private fun zoomAt(factor: Float, focusX: Float, focusY: Float) {
        val worldX = (focusX - originX()) / scale()
        val worldY = (focusY - originY()) / scale()
        zoom = (zoom * factor).coerceIn(if (settings.keyPitchMm() > 0) .25f else 1f, 5f)
        panX += focusX - (originX() + worldX * scale())
        panY += focusY - (originY() + worldY * scale())
        invalidate()
    }

    private fun span(event: MotionEvent): Float = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    private fun hit(layout: KleLayout, screenX: Float, screenY: Float): KleLayout.Key? {
        val worldX = (screenX - originX()) / scale()
        val worldY = (screenY - originY()) / scale()
        for (index in layout.keys.indices.reversed()) {
            val key = layout.keys[index]
            if (key.decal) continue
            val angle = Math.toRadians(key.rotation.toDouble())
            val dx = worldX - key.rx; val dy = worldY - key.ry
            val x = key.rx + dx * cos(angle).toFloat() + dy * sin(angle).toFloat()
            val y = key.ry - dx * sin(angle).toFloat() + dy * cos(angle).toFloat()
            if (inside(x, y, key.x, key.y, key.w, key.h) ||
                inside(x, y, key.x + key.x2, key.y + key.y2, key.w2, key.h2)) return key
        }
        return null
    }

    private fun inside(x: Float, y: Float, left: Float, top: Float, w: Float, h: Float): Boolean =
        x >= left && x < left + w && y >= top && y < top + h

    override fun onDetachedFromWindow() {
        releaseMomentary()
        chordTouches.clear()
        momentaryGesture = false
        trackpadGesture.cancel()
        super.onDetachedFromWindow()
    }
}
