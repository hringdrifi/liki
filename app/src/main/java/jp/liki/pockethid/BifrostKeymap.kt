package jp.liki.pockethid

/** Bifrost's four-layer keymap, in the order of its shared matrix transform. */
internal class BifrostKeymap(private val output: Output) {
    interface Output {
        fun key(position: Int, modifiers: Int, usage: Int, pressed: Boolean)
        fun mouseButton(position: Int, button: Int, pressed: Boolean)
        fun scroll(amount: Int)
        fun layerChanged(layer: Int)
    }

    private sealed interface Action {
        data class Key(val usage: Int, val modifiers: Int = 0) : Action
        data class Layer(val number: Int) : Action
        data class Mouse(val button: Int) : Action
        data class Wheel(val amount: Int) : Action
        data object Transparent : Action
        data object None : Action
    }

    // Mirrors config/bifrost.keymap in zmk-config-bifrost. Keep both in sync.
    private val layers = listOf(
        """TAB Q W E R T Y U I O P SQT BSLH
           ESC A S D F G H J K L SEMI RET
           MO1 Z X C V B N M COMMA DOT FSLH UP MO2
           LCTRL LGUI LALT BSPC SPACE MO1 MO2 SPACE LEFT DOWN RIGHT""",
        """TRANS N1 N2 N3 N4 N5 N6 N7 N8 N9 N0 MINUS EQUAL
           TRANS EXCL AT HASH DLLR PRCNT CARET AMPS STAR LPAR RPAR TRANS
           TRANS GRAVE LBKT RBKT LBRC RBRC MKP_L MKP_M MKP_R MSC_DOWN MSC_UP TRANS TRANS
           TRANS TRANS TRANS DEL TRANS TRANS STUDIO_UNLOCK TRANS HOME PG_DN END""",
        """TRANS F1 F2 F3 F4 F5 F6 F7 F8 F9 F10 F11 F12
           TRANS HOME PG_DN PG_UP END INSERT LEFT DOWN UP RIGHT DEL TRANS
           TRANS LC_Z LC_X LC_C LC_V LC_A TRANS TRANS TRANS TRANS TRANS TRANS TRANS
           TRANS TRANS TRANS DEL TRANS STUDIO_UNLOCK TRANS TRANS HOME PG_DN END""",
        """TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS
           TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS
           TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS
           TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS TRANS"""
    ).map { source ->
        source.trim().split(Regex("\\s+")).map(::parse).also { require(it.size == 49) }
    }

    private val held = mutableMapOf<Int, Action>()
    private val heldLayers = mutableMapOf<Int, Int>()
    private var manualLayer = 0
    var activeLayer: Int = 0
        private set

    fun setManualLayer(layer: Int) {
        manualLayer = layer.coerceIn(0, 3)
        refreshLayer()
    }

    fun setPosition(position: Int, pressed: Boolean) {
        if (position !in 0..48) return
        if (pressed) {
            if (held.containsKey(position)) return
            val action = resolve(position)
            held[position] = action
            when (action) {
                is Action.Key -> output.key(position, action.modifiers, action.usage, true)
                is Action.Layer -> { heldLayers[position] = action.number; refreshLayer() }
                is Action.Mouse -> output.mouseButton(position, action.button, true)
                is Action.Wheel -> output.scroll(action.amount)
                else -> Unit
            }
        } else {
            when (val action = held.remove(position)) {
                is Action.Key -> output.key(position, action.modifiers, action.usage, false)
                is Action.Layer -> { heldLayers.remove(position); refreshLayer() }
                is Action.Mouse -> output.mouseButton(position, action.button, false)
                else -> Unit
            }
        }
    }

    fun releaseAll() {
        held.keys.toList().forEach { setPosition(it, false) }
    }

    private fun refreshLayer() {
        val next = maxOf(manualLayer, heldLayers.values.maxOrNull() ?: 0)
        if (next != activeLayer) {
            activeLayer = next
            output.layerChanged(next)
        }
    }

    private fun resolve(position: Int): Action {
        for (layer in activeLayer downTo 0) {
            val action = layers[layer][position]
            if (action != Action.Transparent) return action
        }
        return Action.None
    }

    private fun parse(token: String): Action {
        if (token == "TRANS") return Action.Transparent
        if (token == "STUDIO_UNLOCK") return Action.None
        if (token == "MO1") return Action.Layer(1)
        if (token == "MO2") return Action.Layer(2)
        if (token.startsWith("MKP_")) return Action.Mouse(when (token) {
            "MKP_L" -> 1; "MKP_R" -> 2; "MKP_M" -> 4
            else -> error("Unknown mouse button $token")
        })
        if (token == "MSC_DOWN") return Action.Wheel(-1)
        if (token == "MSC_UP") return Action.Wheel(1)
        if (token.startsWith("LC_")) return Action.Key(letter(token.last()), HidReports.MOD_CTRL)
        if (token.length == 1 && token[0] in 'A'..'Z') return Action.Key(letter(token[0]))
        if (token.length == 2 && token[0] == 'N' && token[1] in '0'..'9')
            return Action.Key(number(token[1]))
        if (token.startsWith("F") && token.drop(1).toIntOrNull() in 1..12)
            return Action.Key(57 + token.drop(1).toInt())
        val shifted = mapOf("EXCL" to '1', "AT" to '2', "HASH" to '3', "DLLR" to '4',
            "PRCNT" to '5', "CARET" to '6', "AMPS" to '7', "STAR" to '8',
            "LPAR" to '9', "RPAR" to '0')
        shifted[token]?.let { return Action.Key(number(it), HidReports.MOD_SHIFT) }
        return when (token) {
            "LSHIFT" -> Action.Key(0, HidReports.MOD_SHIFT)
            "RSHIFT" -> Action.Key(0, HidReports.MOD_RIGHT_SHIFT)
            "LCTRL" -> Action.Key(0, HidReports.MOD_CTRL)
            "LGUI" -> Action.Key(0, HidReports.MOD_GUI)
            "LALT" -> Action.Key(0, HidReports.MOD_ALT)
            "TAB" -> Action.Key(43); "ESC" -> Action.Key(41)
            "RET" -> Action.Key(40); "BSPC" -> Action.Key(42)
            "SPACE" -> Action.Key(44); "SQT" -> Action.Key(52)
            "BSLH" -> Action.Key(49); "SEMI" -> Action.Key(51)
            "COMMA" -> Action.Key(54); "DOT" -> Action.Key(55)
            "FSLH" -> Action.Key(56); "MINUS" -> Action.Key(45)
            "EQUAL" -> Action.Key(46); "GRAVE" -> Action.Key(53)
            "LBKT" -> Action.Key(47); "RBKT" -> Action.Key(48)
            "LBRC" -> Action.Key(47, HidReports.MOD_SHIFT)
            "RBRC" -> Action.Key(48, HidReports.MOD_SHIFT)
            "UP" -> Action.Key(82); "DOWN" -> Action.Key(81)
            "LEFT" -> Action.Key(80); "RIGHT" -> Action.Key(79)
            "HOME" -> Action.Key(74); "END" -> Action.Key(77)
            "PG_UP" -> Action.Key(75); "PG_DN" -> Action.Key(78)
            "INSERT" -> Action.Key(73); "DEL" -> Action.Key(76)
            else -> error("Unknown Bifrost binding $token")
        }
    }

    private fun letter(value: Char): Int = 4 + (value - 'A')
    private fun number(value: Char): Int = if (value == '0') 39 else 29 + (value - '0')
}
