package jp.liki.pockethid

/** Stateless translation of the complete held-key snapshot. Mappings never chain. */
internal object KeyboardRemapper {
    data class Report(val keyboard: ByteArray, val consumer: Int)
    fun translate(pressed: List<Int>, consumer: Int, rules: Map<Int, List<Int>>, layout: String = "none"): Report {
        val source = pressed.map { 0x70000 or it } + if (consumer != 0) listOf(0xc0000 or consumer) else emptyList()
        val output = source.flatMap { rules[it] ?: listOf(it) }.distinct()
        val keys = output.filter { it ushr 16 == 7 }.map { it and 0xffff }
        val rawModifiers = keys.filter { it in 0xe0..0xe7 }.fold(0) { result, code -> result or (1 shl (code - 0xe0)) }
        val (modifiers, normal) = KeyboardLayoutConverter.convert(rawModifiers, keys.filter { it in 4..0xdd }, layout)
        val codes = if (normal.size > 6) List(6) { 1 } else normal
        return Report(byteArrayOf(modifiers.toByte(), 0) + ByteArray(6) { codes.getOrElse(it) { 0 }.toByte() },
            output.lastOrNull { it ushr 16 == 12 }?.and(0xffff) ?: 0)
    }
}

/** Character-preserving conversion for the standard US and JIS alphanumeric layouts.
 * HID positions and Shift pairs: QMK quantum/keymap_extras/keymap_{us,japanese}.h.
 */
internal object KeyboardLayoutConverter {
    private data class Key(val code: Int, val plain: Char, val shifted: Char)
    private val us = listOf(
        Key(0x1e, '1', '!'), Key(0x1f, '2', '@'), Key(0x20, '3', '#'), Key(0x21, '4', '$'),
        Key(0x22, '5', '%'), Key(0x23, '6', '^'), Key(0x24, '7', '&'), Key(0x25, '8', '*'),
        Key(0x26, '9', '('), Key(0x27, '0', ')'), Key(0x2d, '-', '_'), Key(0x2e, '=', '+'),
        Key(0x2f, '[', '{'), Key(0x30, ']', '}'), Key(0x31, '\\', '|'), Key(0x33, ';', ':'),
        Key(0x34, '\'', '"'), Key(0x35, '`', '~'), Key(0x36, ',', '<'), Key(0x37, '.', '>'), Key(0x38, '/', '?'))
    private val jis = listOf(
        Key(0x1e, '1', '!'), Key(0x1f, '2', '"'), Key(0x20, '3', '#'), Key(0x21, '4', '$'),
        Key(0x22, '5', '%'), Key(0x23, '6', '&'), Key(0x24, '7', '\''), Key(0x25, '8', '('),
        Key(0x26, '9', ')'), Key(0x27, '0', '\u0000'), Key(0x2d, '-', '='), Key(0x2e, '^', '~'),
        Key(0x2f, '@', '`'), Key(0x30, '[', '{'), Key(0x32, ']', '}'), Key(0x33, ';', '+'),
        Key(0x34, ':', '*'), Key(0x87, '\\', '_'), Key(0x89, '\\', '|'),
        Key(0x36, ',', '<'), Key(0x37, '.', '>'), Key(0x38, '/', '?'))

    fun convert(modifiers: Int, keys: List<Int>, layout: String): Pair<Int, List<Int>> {
        if (layout !in listOf("us-jis", "jis-us")) return modifiers to keys
        val source = if (layout == "us-jis") us else jis
        val target = if (layout == "us-jis") jis else us
        val shift = modifiers and 0x22 != 0
        val converted = keys.map { code ->
            // Windows/Android may report the JIS closing bracket as either HID position.
            val position = if (layout == "jis-us" && code == 0x31) 0x32 else code
            val key = source.find { it.code == position }
            val char = key?.let { if (shift) it.shifted else it.plain }
            val match = target.find { char != null && char != '\u0000' && (it.plain == char || it.shifted == char) }
            if (match == null) code to shift else match.code to (match.shifted == char)
        }
        // HID shares one modifier mask across all keys. Do not emit incorrect characters
        // when simultaneous keys require incompatible Shift states; resume on release.
        if (converted.map { it.second }.distinct().size > 1) return modifiers to List(6) { 1 }
        val outputShift = converted.firstOrNull()?.second ?: shift
        val mods = if (outputShift) modifiers or (if (shift) modifiers and 0x22 else 2) else modifiers and 0x22.inv()
        return mods to converted.map { it.first }.distinct()
    }
}
