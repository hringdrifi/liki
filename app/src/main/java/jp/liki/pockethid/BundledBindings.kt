package jp.liki.pockethid

import org.json.JSONObject

/** Key actions are independent of the labels in the bundled KLE layouts. */
internal object BundledBindings {
    val trackpad = listOf("", "Ctrl+Z", "Ctrl+X", "Ctrl+C", "Ctrl+V",
        "Ctrl+Shift+Z", "Ctrl+Y", "Ctrl+A", "Ctrl+S",
        "Mute", "Volume Down", "Volume Up", "Enter")

    val keyboard = listOf(
        "Esc", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
        "`", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "Backspace",
        "Tab", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]", "\\",
        "Caps Lock", "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'", "Enter",
        "Shift", "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/", "Shift",
        "Ctrl", "Win/Cmd", "Alt", "Space", "Alt", "Ctrl", "Left", "Down", "Up", "Right",
        "Print Screen", "Scroll Lock", "Pause", "Insert", "Home", "Page Up",
        "Delete", "End", "Page Down", "Win/Cmd", "Menu"
    )

    fun into(overrides: JSONObject, names: List<String>): JSONObject {
        names.forEachIndexed { index, name ->
            if (name.isNotEmpty() && !overrides.has(index.toString()))
                overrides.put(index.toString(), name)
        }
        return overrides
    }
}
