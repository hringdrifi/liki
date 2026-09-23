package jp.liki.pockethid

import java.util.Locale

internal enum class LayerAction { NONE, TOGGLE, BASE, CYCLE, ONE_SHOT, MOMENTARY }

internal class KeyBinding private constructor(
    @JvmField val name: String,
    @JvmField val code: Int,
    @JvmField val modifier: Int,
    @JvmField val layer: Int = 0,
    @JvmField val layerAction: LayerAction = LayerAction.NONE,
    @JvmField val consumerUsage: Int = 0
) {
    companion object {
        private val OPTIONS = buildOptions()

        @JvmStatic fun options(): List<KeyBinding> = OPTIONS
        @JvmStatic fun named(name: String): KeyBinding? = OPTIONS.firstOrNull { it.name == name }

        @JvmStatic fun forKey(key: KleLayout.Key): KeyBinding? {
            val preference = intArrayOf(4, 0, 6, 2, 3, 5, 7, 8, 1, 9, 10, 11)
            for (index in preference) forLabel(key.labels[index])?.let { return it }
            return null
        }

        @JvmStatic fun forLabel(label: String?): KeyBinding? {
            if (label.isNullOrEmpty()) return null
            val normalized = label.trim().lowercase(Locale.ROOT)
            val standard = when (normalized) {
                "esc", "escape" -> "Esc"
                "backspace", "bksp", "⌫" -> "Backspace"
                "tab" -> "Tab"
                "enter", "return", "↵" -> "Enter"
                "space", "spacebar" -> "Space"
                "caps lock", "caps" -> "Caps Lock"
                "shift", "⇧" -> "Shift"
                "ctrl", "control" -> "Ctrl"
                "alt", "option" -> "Alt"
                "win", "cmd", "command", "meta", "super" -> "Win/Cmd"
                "←", "left" -> "Left"
                "→", "right" -> "Right"
                "↑", "up" -> "Up"
                "↓", "down" -> "Down"
                "del", "delete" -> "Delete"
                "ins", "insert" -> "Insert"
                "pgup", "page up" -> "Page Up"
                "pgdn", "page down" -> "Page Down"
                "home" -> "Home"
                "end" -> "End"
                else -> null
            }
            if (standard != null) return named(standard)
            OPTIONS.firstOrNull { it.name.equals(label.trim(), ignoreCase = true) }?.let { return it }
            if (label.length == 1) {
                val key = HidReports.ascii(label[0])
                if (key != null) return OPTIONS.firstOrNull { it.modifier == 0 && it.code == key[1] }
            }
            return null
        }

        private fun buildOptions(): List<KeyBinding> = buildList {
            fun add(name: String, code: Int) { add(KeyBinding(name, code, 0)) }
            fun addMod(name: String, modifier: Int) { add(KeyBinding(name, 0, modifier)) }
            fun addCombo(name: String, code: Int, modifier: Int) { add(KeyBinding(name, code, modifier)) }
            fun addConsumer(name: String, usage: Int) {
                add(KeyBinding(name, 0, 0, consumerUsage = usage))
            }
            addMod("Ctrl", HidReports.MOD_CTRL)
            addMod("Shift", HidReports.MOD_SHIFT)
            addMod("Alt", HidReports.MOD_ALT)
            addMod("Win/Cmd", HidReports.MOD_GUI)
            add(KeyBinding("レイヤー0へ戻る", 0, 0, 0, LayerAction.BASE))
            add(KeyBinding("レイヤー1切り替え", 0, 0, 1, LayerAction.TOGGLE))
            add(KeyBinding("レイヤー2切り替え", 0, 0, 2, LayerAction.TOGGLE))
            add(KeyBinding("次のレイヤー", 0, 0, 0, LayerAction.CYCLE))
            add(KeyBinding("次の1キーだけレイヤー1", 0, 0, 1, LayerAction.ONE_SHOT))
            add(KeyBinding("次の1キーだけレイヤー2", 0, 0, 2, LayerAction.ONE_SHOT))
            add(KeyBinding("押している間レイヤー1", 0, 0, 1, LayerAction.MOMENTARY))
            add(KeyBinding("押している間レイヤー2", 0, 0, 2, LayerAction.MOMENTARY))
            addCombo("Ctrl+Z", 29, HidReports.MOD_CTRL)
            addCombo("Ctrl+Shift+Z", 29, HidReports.MOD_CTRL or HidReports.MOD_SHIFT)
            addCombo("Ctrl+Y", 28, HidReports.MOD_CTRL)
            addCombo("Ctrl+X", 27, HidReports.MOD_CTRL)
            addCombo("Ctrl+C", 6, HidReports.MOD_CTRL)
            addCombo("Ctrl+V", 25, HidReports.MOD_CTRL)
            addCombo("Ctrl+S", 22, HidReports.MOD_CTRL)
            addCombo("Ctrl+A", 4, HidReports.MOD_CTRL)
            addConsumer("Play/Pause", 0xCD)
            addConsumer("Next Track", 0xB5)
            addConsumer("Previous Track", 0xB6)
            addConsumer("Stop", 0xB7)
            addConsumer("Volume Up", 0xE9)
            addConsumer("Volume Down", 0xEA)
            addConsumer("Mute", 0xE2)
            for (char in 'A'..'Z') add(char.toString(), 4 + (char - 'A'))
            for (char in '1'..'9') add(char.toString(), 30 + (char - '1'))
            add("0", 39)
            add("Enter", 40); add("Esc", 41); add("Backspace", 42)
            add("Tab", 43); add("Space", 44)
            add("-", 45); add("=", 46); add("[", 47); add("]", 48)
            add("\\", 49); add(";", 51); add("'", 52); add("`", 53)
            add(",", 54); add(".", 55); add("/", 56)
            add("Caps Lock", 57)
            for (number in 1..12) add("F$number", 57 + number)
            add("Print Screen", 70); add("Scroll Lock", 71); add("Pause", 72)
            add("Insert", 73); add("Home", 74); add("Page Up", 75)
            add("Delete", 76); add("End", 77); add("Page Down", 78)
            add("Right", 79); add("Left", 80); add("Down", 81); add("Up", 82)
            add("Num Lock", 83); add("Numpad /", 84); add("Numpad *", 85)
            add("Numpad -", 86); add("Numpad +", 87); add("Numpad Enter", 88)
            for (number in 1..9) add("Numpad $number", 88 + number)
            add("Numpad 0", 98); add("Numpad .", 99)
            add("Menu", 101)
        }
    }
}
