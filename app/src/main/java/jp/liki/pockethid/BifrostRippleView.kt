package jp.liki.pockethid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.hypot

/** Visual feedback for physical Bifrost presses; never handles touch input. */
internal class BifrostRippleView(context: Context) : View(context) {
    private data class Ripple(val left: Boolean, val inward: Float, val row: Int, val started: Long)
    private val ripples = ArrayDeque<Ripple>()
    private val durationMillis = 1600L
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppColors.ACCENT
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun press(position: Int) {
        if (position !in 0..48 || !ValueAnimator.areAnimatorsEnabled()) return
        // Shared matrix order: each row contains the left keys, then the right keys.
        val row = when (position) { in 0..12 -> 0; in 13..24 -> 1; in 25..37 -> 2; else -> 3 }
        val start = intArrayOf(0, 13, 25, 38)[row]
        val rightCount = intArrayOf(7, 6, 7, 5)[row]
        val column = position - start
        val left = column < 6
        val inward = if (left) column / 5f else 1f - (column - 6) / (rightCount - 1f)
        if (ripples.size >= 24) ripples.removeFirst()
        ripples.addLast(Ripple(left, inward, row, SystemClock.uptimeMillis()))
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        while (ripples.isNotEmpty() && now - ripples.first().started >= durationMillis) ripples.removeFirst()
        val radiusLimit = hypot(width.toFloat(), height.toFloat()) * .65f
        for (ripple in ripples) {
            val progress = ((now - ripple.started) / durationMillis.toFloat()).coerceIn(0f, 1f)
            val offset = (48f - 24f * ripple.inward) * density
            val x = if (ripple.left) -offset else width + offset
            val y = height * (.35f + .1f * ripple.row)
            paint.alpha = (110f * (1f - progress) * (1f - progress)).toInt()
            canvas.drawCircle(x, y, offset + 8f * density + radiusLimit * progress, paint)
        }
        if (ripples.isNotEmpty()) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        ripples.clear()
        super.onDetachedFromWindow()
    }
}
