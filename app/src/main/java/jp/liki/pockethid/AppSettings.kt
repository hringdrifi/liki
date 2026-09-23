package jp.liki.pockethid

import android.content.Context

internal class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun stickyModifiers(): Boolean = preferences.getBoolean("sticky_modifiers", true)
    fun setStickyModifiers(enabled: Boolean) = save("sticky_modifiers", enabled)

    fun longPressBinding(): Boolean = preferences.getBoolean("long_press_binding", true)
    fun setLongPressBinding(enabled: Boolean) = save("long_press_binding", enabled)

    fun pinchZoom(): Boolean = preferences.getBoolean("pinch_zoom", true)
    fun setPinchZoom(enabled: Boolean) = save("pinch_zoom", enabled)

    fun dragMoveKeyboard(): Boolean = preferences.getBoolean("drag_move_keyboard", true)
    fun setDragMoveKeyboard(enabled: Boolean) = save("drag_move_keyboard", enabled)

    fun keepScreenOn(): Boolean = preferences.getBoolean("keep_screen_on", false)
    fun setKeepScreenOn(enabled: Boolean) = save("keep_screen_on", enabled)

    fun touchVibration(): Boolean = preferences.getBoolean("touch_vibration", false)
    fun setTouchVibration(enabled: Boolean) = save("touch_vibration", enabled)

    // 0 follows the device setting; 1 and 2 lock portrait and landscape.
    fun screenOrientation(): Int = preferences.getInt("screen_orientation", 0).coerceIn(0, 2)
    fun setScreenOrientation(orientation: Int) {
        preferences.edit().putInt("screen_orientation", orientation.coerceIn(0, 2)).apply()
    }

    // Zero keeps the existing fit-to-screen behavior.
    fun keyPitchMm(): Float = preferences.getFloat("key_pitch_mm", 0f)
    fun setKeyPitchMm(pitchMm: Float) {
        preferences.edit().putFloat("key_pitch_mm", if (pitchMm <= 0f) 0f else pitchMm.coerceIn(3f, 24f)).apply()
    }

    // Preserve the initial fit-to-screen key size when the display orientation changes.
    fun autoKeyScalePx(): Float = preferences.getFloat("auto_key_scale_px", 0f).takeIf { it.isFinite() && it > 0f } ?: 0f
    fun setAutoKeyScalePx(scale: Float) {
        if (scale.isFinite() && scale > 0f) preferences.edit().putFloat("auto_key_scale_px", scale).apply()
    }
    fun clearAutoKeyScalePx() { preferences.edit().remove("auto_key_scale_px").apply() }

    fun tapToClick(): Boolean = preferences.getBoolean("tap_to_click", true)
    fun setTapToClick(enabled: Boolean) = save("tap_to_click", enabled)

    fun holdToDrag(): Boolean = preferences.getBoolean("hold_to_drag", true)
    fun setHoldToDrag(enabled: Boolean) = save("hold_to_drag", enabled)

    fun twoFingerScroll(): Boolean = preferences.getBoolean("two_finger_scroll", true)
    fun setTwoFingerScroll(enabled: Boolean) = save("two_finger_scroll", enabled)

    fun trackpadPinchZoom(): Boolean = preferences.getBoolean("trackpad_pinch_zoom", true)
    fun setTrackpadPinchZoom(enabled: Boolean) = save("trackpad_pinch_zoom", enabled)

    fun twoFingerRightClick(): Boolean = preferences.getBoolean("two_finger_right_click", true)
    fun setTwoFingerRightClick(enabled: Boolean) = save("two_finger_right_click", enabled)

    fun tapDrag(): Boolean = preferences.getBoolean("tap_drag", true)
    fun setTapDrag(enabled: Boolean) = save("tap_drag", enabled)

    fun threeFingerMiddleClick(): Boolean = preferences.getBoolean("three_finger_middle_click", true)
    fun setThreeFingerMiddleClick(enabled: Boolean) = save("three_finger_middle_click", enabled)

    fun pointerSpeed(): Float = preferences.getFloat("pointer_speed", 1f).coerceIn(0.5f, 3f)
    fun setPointerSpeed(speed: Float) = saveSpeed("pointer_speed", speed)

    // 0 sends every movement immediately; 1 and 2 combine movement over 16/32 ms.
    fun pointerSendMode(): Int = preferences.getInt("pointer_send_mode", 2).coerceIn(0, 2)
    fun setPointerSendMode(mode: Int) {
        preferences.edit().putInt("pointer_send_mode", mode.coerceIn(0, 2)).apply()
    }

    fun scrollSpeed(): Float = preferences.getFloat("scroll_speed", 1f).coerceIn(0.5f, 3f)
    fun setScrollSpeed(speed: Float) = saveSpeed("scroll_speed", speed)

    fun reverseScroll(): Boolean = preferences.getBoolean("reverse_scroll", false)
    fun setReverseScroll(enabled: Boolean) = save("reverse_scroll", enabled)

    private fun saveSpeed(key: String, speed: Float) {
        if (speed.isFinite()) preferences.edit().putFloat(key, speed.coerceIn(0.5f, 3f)).apply()
    }

    private fun save(key: String, enabled: Boolean) {
        preferences.edit().putBoolean(key, enabled).apply()
    }
}
