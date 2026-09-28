package jp.liki.pockethid

import java.io.ByteArrayOutputStream

/** Read-only subset of the ZMK Studio v3 protocol used for keyboard display. */
internal object StudioProtocol {
    data class Key(val width: Int, val height: Int, val x: Int, val y: Int,
                   val rotation: Int, val rx: Int, val ry: Int)
    data class Layout(val name: String, val keys: List<Key>)
    data class Binding(val behaviorId: Int, val param1: Long)
    data class Snapshot(val layout: Layout, val bindings: List<Binding>)

    private fun varint(value: Long): ByteArray {
        var n = value
        val out = ByteArrayOutputStream()
        while (n >= 128) { out.write(((n and 127) or 128).toInt()); n = n ushr 7 }
        out.write(n.toInt())
        return out.toByteArray()
    }

    private fun field(number: Int, bytes: ByteArray): ByteArray =
        varint((number * 8 + 2).toLong()) + varint(bytes.size.toLong()) + bytes

    fun request(id: Int, keymapRequest: Int): ByteArray {
        val inner = varint((keymapRequest * 8).toLong()) + byteArrayOf(1)
        val message = varint(8) + varint(id.toLong()) + field(5, inner)
        val out = ByteArrayOutputStream()
        out.write(0xab)
        message.forEach { byte ->
            val n = byte.toInt() and 255
            if (n in 0xab..0xad) out.write(0xac)
            out.write(n)
        }
        out.write(0xad)
        return out.toByteArray()
    }

    class Frames {
        private val current = ByteArrayOutputStream()
        private var started = false
        private var escaped = false
        fun feed(bytes: ByteArray): List<ByteArray> {
            val result = mutableListOf<ByteArray>()
            bytes.forEach { byte ->
                val n = byte.toInt() and 255
                when {
                    !started -> if (n == 0xab) { started = true; current.reset() }
                    escaped -> { current.write(n); escaped = false }
                    n == 0xac -> escaped = true
                    n == 0xad -> { result.add(current.toByteArray()); current.reset(); started = false }
                    n == 0xab -> current.reset()
                    current.size() < 65536 -> current.write(n)
                    else -> { current.reset(); started = false }
                }
            }
            return result
        }
    }

    private data class Entry(val number: Int, val data: ByteArray, val value: Long)
    private fun entries(bytes: ByteArray): List<Entry> {
        val result = mutableListOf<Entry>()
        var at = 0
        while (at < bytes.size) {
            var shift = 0; var tag = 0L
            do {
                if (at >= bytes.size || shift > 63) return emptyList()
                val b = bytes[at++].toInt() and 255
                tag = tag or ((b and 127).toLong() shl shift)
                shift += 7
                if (b < 128) break
            } while (true)
            val wire = (tag and 7).toInt()
            val number = (tag ushr 3).toInt()
            if (number == 0) return emptyList()
            var value = 0L
            if (wire == 0) {
                shift = 0
                do {
                    if (at >= bytes.size || shift > 63) return emptyList()
                    val b = bytes[at++].toInt() and 255
                    value = value or ((b and 127).toLong() shl shift)
                    shift += 7
                    if (b < 128) break
                } while (true)
                result.add(Entry(number, byteArrayOf(), value))
            } else if (wire == 2) {
                shift = 0
                do {
                    if (at >= bytes.size || shift > 28) return emptyList()
                    val b = bytes[at++].toInt() and 255
                    value = value or ((b and 127).toLong() shl shift)
                    shift += 7
                    if (b < 128) break
                } while (true)
                if (value > bytes.size - at) return emptyList()
                result.add(Entry(number, bytes.copyOfRange(at, at + value.toInt()), value))
                at += value.toInt()
            } else return emptyList()
        }
        return result
    }

    private fun List<Entry>.message(number: Int): List<Entry> =
        firstOrNull { it.number == number }?.data?.let(::entries).orEmpty()
    private fun List<Entry>.number(number: Int): Int = firstOrNull { it.number == number }?.value?.toInt() ?: 0
    private fun List<Entry>.string(number: Int): String =
        firstOrNull { it.number == number }?.data?.toString(Charsets.UTF_8).orEmpty()
    private fun signed(value: Int): Int = (value ushr 1) xor -(value and 1)

    /** Returns request id and its keymap response body, if this is one. */
    fun keymapResponse(frame: ByteArray): Pair<Int, List<ByteArray>>? {
        val request = entries(frame).message(1)
        val id = request.number(1)
        val keymap = request.message(5)
        if (id == 0 || keymap.isEmpty()) return null
        return id to keymap.map { field -> byteArrayOf(field.number.toByte()) + field.data }
    }

    fun physicalLayouts(body: ByteArray): Layout? {
        val fields = entries(body)
        val active = fields.number(1)
        val layouts = fields.filter { it.number == 2 }.map { item ->
            val parts = entries(item.data)
            Layout(parts.string(1), parts.filter { it.number == 2 }.map { key ->
                val attrs = entries(key.data)
                Key(signed(attrs.number(1)), signed(attrs.number(2)),
                    signed(attrs.number(3)), signed(attrs.number(4)),
                    signed(attrs.number(5)), signed(attrs.number(6)), signed(attrs.number(7)))
            })
        }
        return layouts.getOrNull(active)?.takeIf { it.keys.isNotEmpty() && it.keys.size <= 300 }
    }

    fun baseBindings(body: ByteArray): List<Binding> {
        val layer = entries(body).firstOrNull { it.number == 1 }?.data ?: return emptyList()
        return entries(layer).filter { it.number == 3 }.map { item ->
            val parts = entries(item.data)
            Binding(signed(parts.number(1)), parts.firstOrNull { it.number == 2 }?.value ?: 0)
        }
    }
}
