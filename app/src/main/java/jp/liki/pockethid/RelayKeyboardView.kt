package jp.liki.pockethid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/** Read-only physical view; HID usages are shown separately when positions are ambiguous. */
internal class RelayKeyboardView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var layout: StudioProtocol.Layout? = null
    private var bindings = emptyList<StudioProtocol.Binding>()
    private var pressed = emptySet<Int>()
    private var modifiers = 0

    fun setKeyboard(layout: StudioProtocol.Layout?, bindings: List<StudioProtocol.Binding>) {
        this.layout = layout; this.bindings = bindings
        invalidate()
    }

    fun setPressed(usages: Set<Int>, modifiers: Int) {
        pressed = usages; this.modifiers = modifiers; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val keys = layout?.keys ?: return
        val bounds = RectF()
        keys.forEach { key ->
            val w = max(key.width, 1).toFloat(); val h = max(key.height, 1).toFloat()
            val x = key.x.toFloat(); val y = key.y.toFloat()
            bounds.union(x, y); bounds.union(x + w, y + h)
        }
        val scale = minOf((width - 24f) / max(bounds.width(), 1f),
            (height - 24f) / max(bounds.height(), 1f))
        canvas.save()
        canvas.translate((width - bounds.width() * scale) / 2 - bounds.left * scale,
            (height - bounds.height() * scale) / 2 - bounds.top * scale)
        canvas.scale(scale, scale)
        keys.forEachIndexed { index, key ->
            val w = max(key.width, 1).toFloat(); val h = max(key.height, 1).toFloat()
            val usage = bindings.getOrNull(index)?.param1?.let { value ->
                if (value ushr 16 == 7L) (value and 0xffff).toInt()
                else null
            }
            val down = usage != null && (usage in pressed ||
                usage in 0xe0..0xe7 && modifiers and (1 shl (usage - 0xe0)) != 0)
            canvas.save()
            canvas.rotate(key.rotation / 100f, key.rx.toFloat(), key.ry.toFloat())
            paint.color = if (down) AppColors.ACCENT else Color.rgb(47, 59, 71)
            paint.style = Paint.Style.FILL
            val inset = minOf(w, h) * .035f
            canvas.drawRoundRect(key.x + inset, key.y + inset,
                key.x + w - inset, key.y + h - inset, minOf(w, h) * .1f,
                minOf(w, h) * .1f, paint)
            paint.color = if (down) Color.BLACK else AppColors.TEXT
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = minOf(w, h) * .27f
            val label = usage?.let(::usageName) ?: "${index + 1}"
            canvas.drawText(label, key.x + w / 2, key.y + h / 2 + paint.textSize / 3, paint)
            canvas.restore()
        }
        canvas.restore()
    }

    companion object {
        fun usageName(usage: Int): String = when (usage) {
            in 4..29 -> ('A' + usage - 4).toString()
            in 30..38 -> (usage - 29).toString()
            39 -> "0"
            40 -> "Enter"; 41 -> "Esc"; 42 -> "⌫"; 43 -> "Tab"; 44 -> "Space"
            45 -> "-"; 46 -> "="; 47 -> "["; 48 -> "]"; 49 -> "\\"
            51 -> ";"; 52 -> "'"; 53 -> "`"; 54 -> ","; 55 -> "."; 56 -> "/"
            in 58..69 -> "F${usage - 57}"
            79 -> "→"; 80 -> "←"; 81 -> "↓"; 82 -> "↑"
            224 -> "Ctrl"; 225 -> "Shift"; 226 -> "Alt"; 227 -> "Win"
            228 -> "Ctrl"; 229 -> "Shift"; 230 -> "Alt"; 231 -> "Win"
            else -> "0x${usage.toString(16)}"
        }
    }
}
