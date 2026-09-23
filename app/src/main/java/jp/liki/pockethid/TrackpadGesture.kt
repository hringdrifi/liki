package jp.liki.pockethid

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

internal class TrackpadGesture(
    private val view: View,
    private val hid: HidController,
    private val settings: AppSettings
) {
    private companion object {
        const val GESTURE_UNDECIDED = 0
        const val GESTURE_SCROLL = 1
        const val GESTURE_ZOOM = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density = view.resources.displayMetrics.density

    private var lastX = 0f; private var lastY = 0f
    private var downX = 0f; private var downY = 0f
    private var remainderX = 0f; private var remainderY = 0f; private var scrollRemainder = 0f
    private var downTime = 0L
    private var lastTapTime = 0L; private var lastTapX = 0f; private var lastTapY = 0f
    private var moved = false; private var dragging = false; private var scrolling = false
    private var tapDragArmed = false; private var maxPointers = 1; private var multiMoved = false
    private var twoFingerGesture = GESTURE_UNDECIDED
    private var startSpan = 0f; private var lastSpan = 0f
    private var startCenterX = 0f; private var startCenterY = 0f
    private var zoomRemainder = 0f
    private val multiStarts = mutableMapOf<Int, Pair<Float, Float>>()
    private val hold = Runnable {
        if (settings.holdToDrag() && !moved && !scrolling) {
            dragging = true
            hid.pressMouse(1)
        }
    }

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; downX = lastX
                lastY = event.y; downY = lastY
                downTime = event.eventTime
                tapDragArmed = settings.tapDrag() && settings.tapToClick() &&
                    lastTapTime > 0 && event.eventTime - lastTapTime in 1..350 &&
                    hypot(downX - lastTapX, downY - lastTapY) <= 24 * density
                remainderX = 0f; remainderY = 0f; scrollRemainder = 0f
                moved = false; dragging = false; scrolling = false
                maxPointers = 1; multiMoved = false; multiStarts.clear()
                twoFingerGesture = GESTURE_UNDECIDED; zoomRemainder = 0f
                if (settings.holdToDrag()) handler.postDelayed(hold, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(hold)
                val wasDragging = dragging
                if (dragging) {
                    hid.releaseMouse(1)
                    dragging = false
                }
                if (!scrolling) {
                    multiStarts.clear()
                    for (index in 0 until event.pointerCount)
                        multiStarts[event.getPointerId(index)] = event.getX(index) to event.getY(index)
                } else {
                    val index = event.actionIndex
                    multiStarts[event.getPointerId(index)] = event.getX(index) to event.getY(index)
                }
                scrolling = true
                multiMoved = multiMoved || wasDragging
                maxPointers = maxOf(maxPointers, event.pointerCount)
                tapDragArmed = false
                lastTapTime = 0L
                lastY = centerY(event)
                if (event.pointerCount == 2) {
                    startSpan = span(event); lastSpan = startSpan
                    startCenterX = centerX(event); startCenterY = lastY
                    twoFingerGesture = GESTURE_UNDECIDED
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    updateMultiMovement(event)
                    if (event.pointerCount == 2 && maxPointers == 2 && multiMoved) {
                        val y = centerY(event)
                        val currentSpan = span(event)
                        if (twoFingerGesture == GESTURE_UNDECIDED) {
                            val spanChange = abs(currentSpan - startSpan)
                            val centerTravel = hypot(centerX(event) - startCenterX, y - startCenterY)
                            twoFingerGesture = when {
                                settings.trackpadPinchZoom() && spanChange > 12 * density &&
                                    spanChange > centerTravel * 1.2f -> GESTURE_ZOOM
                                centerTravel > 8 * density &&
                                    (centerTravel > spanChange * .8f || !settings.trackpadPinchZoom()) -> GESTURE_SCROLL
                                else -> GESTURE_UNDECIDED
                            }
                        }
                        when (twoFingerGesture) {
                            GESTURE_SCROLL -> {
                                val direction = if (settings.reverseScroll()) -1f else 1f
                                val delta = (lastY - y) / (18 * density) * settings.scrollSpeed() * direction + scrollRemainder
                                val ticks = delta.toInt()
                                scrollRemainder = delta - ticks
                                if (ticks != 0 && settings.twoFingerScroll()) hid.scroll(ticks)
                                lastY = y
                            }
                            GESTURE_ZOOM -> {
                                val delta = (currentSpan - lastSpan) / (24 * density) + zoomRemainder
                                val ticks = delta.toInt()
                                zoomRemainder = delta - ticks
                                if (ticks != 0) hid.zoom(ticks)
                                lastSpan = currentSpan
                            }
                        }
                    }
                } else if (!scrolling) {
                    val x = event.x; val y = event.y
                    if (hypot(x - downX, y - downY) > 8 * density) {
                        moved = true
                        handler.removeCallbacks(hold)
                        if (tapDragArmed && !dragging) {
                            dragging = true
                            hid.pressMouse(1)
                            lastTapTime = 0L
                        }
                    }
                    if (tapDragArmed && !dragging && !moved) return
                    val speed = 1.45f * settings.pointerSpeed()
                    val dx = (x - lastX) * speed + remainderX
                    val dy = (y - lastY) * speed + remainderY
                    val ix = dx.toInt(); val iy = dy.toInt()
                    remainderX = dx - ix; remainderY = dy - iy
                    if (ix != 0 || iy != 0) hid.mouseMove(ix, iy)
                    lastX = x; lastY = y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                updateMultiMovement(event)
                if (event.pointerCount == 2) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining); lastY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(hold)
                if (scrolling) updateMultiMovement(event)
                if (dragging) hid.releaseMouse(1)
                else if (event.actionMasked == MotionEvent.ACTION_UP &&
                    scrolling && !multiMoved && event.eventTime - downTime < 450) {
                    if (maxPointers == 2 && settings.twoFingerRightClick()) click(2)
                    if (maxPointers == 3 && settings.threeFingerMiddleClick()) click(4)
                } else if (event.actionMasked == MotionEvent.ACTION_UP && settings.tapToClick() &&
                    !moved && !scrolling && event.eventTime - downTime < 450) {
                    click(1)
                    lastTapTime = event.eventTime
                    lastTapX = event.x; lastTapY = event.y
                }
                if (dragging || scrolling || moved || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    lastTapTime = 0L
                dragging = false; scrolling = false; tapDragArmed = false
                twoFingerGesture = GESTURE_UNDECIDED
                multiStarts.clear()
            }
        }
    }

    fun cancel() {
        handler.removeCallbacks(hold)
        if (dragging) hid.releaseMouse(1)
        dragging = false; scrolling = false; tapDragArmed = false
        twoFingerGesture = GESTURE_UNDECIDED
        lastTapTime = 0L
        multiStarts.clear()
    }

    private fun updateMultiMovement(event: MotionEvent) {
        for (index in 0 until event.pointerCount) {
            val start = multiStarts[event.getPointerId(index)] ?: continue
            if (hypot(event.getX(index) - start.first, event.getY(index) - start.second) > 8 * density)
                multiMoved = true
        }
    }

    private fun centerY(event: MotionEvent): Float = (event.getY(0) + event.getY(1)) / 2f
    private fun centerX(event: MotionEvent): Float = (event.getX(0) + event.getX(1)) / 2f
    private fun span(event: MotionEvent): Float = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    private fun click(button: Int) {
        if (settings.touchVibration()) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        hid.clickMouse(button)
    }
}
