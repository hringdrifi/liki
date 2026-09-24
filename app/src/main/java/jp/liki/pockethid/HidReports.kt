package jp.liki.pockethid

internal object HidReports {
    const val KEYBOARD_ID = 1
    const val MOUSE_ID = 2
    const val CONSUMER_ID = 3
    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_GUI = 0x08
    const val MOD_RIGHT_CTRL = 0x10
    const val MOD_RIGHT_SHIFT = 0x20
    const val MOD_RIGHT_ALT = 0x40
    const val MOD_RIGHT_GUI = 0x80
    const val KEY_ENTER = 0x28
    const val KEY_ESC = 0x29
    const val KEY_BACKSPACE = 0x2a
    const val KEY_TAB = 0x2b
    const val KEY_SPACE = 0x2c
    const val KEY_RIGHT = 0x4f
    const val KEY_LEFT = 0x50
    const val KEY_DOWN = 0x51
    const val KEY_UP = 0x52

    // Report 1: boot-style keyboard with one LED output byte.
    // Report 2: three-button relative mouse with vertical wheel.
    // Report 3: one 16-bit Consumer Control usage for media keys.
    @JvmField val DESCRIPTOR = byteArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xa1.toByte(), 0x01, 0x85.toByte(), 0x01,
        0x05, 0x07, 0x19, 0xe0.toByte(), 0x29, 0xe7.toByte(), 0x15, 0x00,
        0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
        0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
        0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x95.toByte(), 0x05,
        0x75, 0x01, 0x91.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x03,
        0x91.toByte(), 0x01, 0xc0.toByte(),
        0x05, 0x01, 0x09, 0x02, 0xa1.toByte(), 0x01, 0x85.toByte(), 0x02,
        0x09, 0x01, 0xa1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00,
        0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, 0x81.toByte(), 0x25, 0x7f, 0x75, 0x08, 0x95.toByte(), 0x03,
        0x81.toByte(), 0x06, 0xc0.toByte(), 0xc0.toByte(),
        0x05, 0x0c, 0x09, 0x01, 0xa1.toByte(), 0x01, 0x85.toByte(), 0x03,
        0x15, 0x00, 0x26, 0xff.toByte(), 0x03,
        0x19, 0x00, 0x2a, 0xff.toByte(), 0x03,
        0x75, 0x10, 0x95.toByte(), 0x01, 0x81.toByte(), 0x00, 0xc0.toByte()
    )

    @JvmStatic fun keyboard(modifiers: Int, key: Int): ByteArray =
        byteArrayOf(modifiers.toByte(), 0, key.toByte(), 0, 0, 0, 0, 0)

    @JvmStatic fun mouse(buttons: Int, x: Int, y: Int, wheel: Int): ByteArray =
        byteArrayOf(buttons.toByte(), x.toByte(), y.toByte(), wheel.toByte())

    @JvmStatic fun consumer(usage: Int): ByteArray =
        byteArrayOf(usage.toByte(), (usage shr 8).toByte())

    @JvmStatic fun ascii(char: Char): IntArray? {
        if (char in 'a'..'z') return intArrayOf(0, 4 + (char - 'a'))
        if (char in 'A'..'Z') return intArrayOf(MOD_SHIFT, 4 + (char - 'A'))
        if (char in '1'..'9') return intArrayOf(0, 30 + (char - '1'))
        if (char == '0') return intArrayOf(0, 39)
        val plain = " -=[]\\;',./`"
        val plainCodes = intArrayOf(44, 45, 46, 47, 48, 49, 51, 52, 54, 55, 56, 53)
        val plainIndex = plain.indexOf(char)
        if (plainIndex >= 0) return intArrayOf(0, plainCodes[plainIndex])
        val shifted = "_+{}|:\"<>?~!@#$%^&*()"
        val shiftedCodes = intArrayOf(45, 46, 47, 48, 49, 51, 52, 54, 55, 56, 53,
            30, 31, 32, 33, 34, 35, 36, 37, 38, 39)
        val shiftedIndex = shifted.indexOf(char)
        if (shiftedIndex >= 0) return intArrayOf(MOD_SHIFT, shiftedCodes[shiftedIndex])
        if (char == '\n' || char == '\r') return intArrayOf(0, KEY_ENTER)
        if (char == '\t') return intArrayOf(0, KEY_TAB)
        return null
    }
}
