package jp.liki.pockethid

/** Pressed HID usages, kept separately for each physical input device. No input history is stored. */
internal class RelayKeyState {
    private val keys = linkedMapOf<Int, MutableSet<Int>>()
    private val consumers = linkedMapOf<Pair<Int, Int>, Int>()

    fun key(device: Int, usage: Int, down: Boolean) {
        if (down) keys.getOrPut(device) { linkedSetOf() }.add(usage)
        else keys[device]?.let { it.remove(usage); if (it.isEmpty()) keys.remove(device) }
    }

    fun consumer(device: Int, key: Int, usage: Int, down: Boolean) {
        if (down) consumers[device to key] = usage else consumers.remove(device to key)
    }

    fun remove(device: Int) { keys.remove(device); consumers.keys.removeAll { it.first == device } }
    fun clear() { keys.clear(); consumers.clear() }
    fun consumerUsage(): Int = consumers.values.lastOrNull() ?: 0
    fun pressedUsages(): List<Int> = keys.values.flatten().distinct()
    fun keyboardReport(): ByteArray {
        val pressed = keys.values.flatten().toSet()
        val modifiers = pressed.filter { it in 0xe0..0xe7 }.fold(0) { result, usage -> result or (1 shl (usage - 0xe0)) }
        val normal = pressed.filter { it in 4..0xdd }
        // Six-key boot/report format. ErrorRollOver prevents silently dropping held keys.
        val usages = if (normal.size > 6) List(6) { 1 } else normal
        return byteArrayOf(modifiers.toByte(), 0) + ByteArray(6) { usages.getOrElse(it) { 0 }.toByte() }
    }
}
