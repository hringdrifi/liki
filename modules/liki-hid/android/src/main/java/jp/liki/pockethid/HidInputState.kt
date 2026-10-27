package jp.liki.pockethid

/** Keep virtual pulses separate from physical keys so either source can release independently. */
internal class HidInputState {
    var physicalKeyboard = ByteArray(8)
    var virtualModifiers = 0
    var virtualCode = 0
    var physicalConsumer = 0
    var virtualConsumer = 0
    var physicalButtons = 0
    fun keyboard(): ByteArray {
        val held = physicalKeyboard.drop(2).map { it.toInt() and 255 }.filter { it != 0 }
        val keys = (held + listOf(virtualCode).filter { it != 0 }).distinct()
        val usages = if (1 in held || keys.size > 6) List(6) { 1 } else keys
        return byteArrayOf(((physicalKeyboard.firstOrNull()?.toInt() ?: 0) or virtualModifiers).toByte(), 0) +
            ByteArray(6) { usages.getOrElse(it) { 0 }.toByte() }
    }
    fun consumer() = if (virtualConsumer != 0) virtualConsumer else physicalConsumer
    fun clearVirtual() { virtualModifiers = 0; virtualCode = 0; virtualConsumer = 0 }
    fun clear() { physicalKeyboard = ByteArray(8); physicalConsumer = 0; physicalButtons = 0; clearVirtual() }
}
