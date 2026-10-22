package jp.liki.pockethid

/** Stateless translation of the complete held-key snapshot. Mappings never chain. */
internal object KeyboardRemapper {
    data class Report(val keyboard: ByteArray, val consumer: Int)
    fun translate(pressed: List<Int>, consumer: Int, rules: Map<Int, List<Int>>): Report {
        val source = pressed.map { 0x70000 or it } + if (consumer != 0) listOf(0xc0000 or consumer) else emptyList()
        val output = source.flatMap { rules[it] ?: listOf(it) }.distinct()
        val keys = output.filter { it ushr 16 == 7 }.map { it and 0xffff }
        val modifiers = keys.filter { it in 0xe0..0xe7 }.fold(0) { result, code -> result or (1 shl (code - 0xe0)) }
        val normal = keys.filter { it in 4..0xdd }
        val codes = if (normal.size > 6) List(6) { 1 } else normal
        return Report(byteArrayOf(modifiers.toByte(), 0) + ByteArray(6) { codes.getOrElse(it) { 0 }.toByte() },
            output.lastOrNull { it ushr 16 == 12 }?.and(0xffff) ?: 0)
    }
}
