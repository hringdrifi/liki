package jp.liki.pockethid

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class KleLayout private constructor(
    @JvmField val name: String,
    @JvmField val keys: List<Key>
) {
    internal class Key {
        @JvmField var index = 0
        @JvmField var x = 0f
        @JvmField var y = 0f
        @JvmField var w = 1f
        @JvmField var h = 1f
        @JvmField var x2 = 0f
        @JvmField var y2 = 0f
        @JvmField var w2 = 1f
        @JvmField var h2 = 1f
        @JvmField var rx = 0f
        @JvmField var ry = 0f
        @JvmField var rotation = 0f
        @JvmField var color = Color.rgb(217, 224, 233)
        @JvmField var labels = Array(12) { "" }
        @JvmField var decal = false
        @JvmField var ghost = false

        fun displayLabel(): String = labels.firstOrNull { it.isNotEmpty() } ?: ""
    }

    companion object {
        @JvmStatic fun parse(json: String): KleLayout {
            val rows = JSONArray(json)
            if (rows.length() == 0) throw JSONException("KLEの行がありません")
            var name = "KLEキーボード"
            val keys = mutableListOf<Key>()
            var x = 0f; var y = 0f; var rx = 0f; var ry = 0f; var rotation = 0f
            var color = Color.rgb(217, 224, 233)
            var align = 4
            var ghost = false
            for (rowIndex in 0 until rows.length()) {
                val rowObject = rows.get(rowIndex)
                if (rowIndex == 0 && rowObject is JSONObject) {
                    name = rowObject.optString("name", name)
                    continue
                }
                if (rowObject !is JSONArray) throw JSONException("KLEの行は配列である必要があります")
                var w = 1f; var h = 1f; var x2 = 0f; var y2 = 0f; var w2 = 0f; var h2 = 0f
                var decal = false
                x = rx
                for (index in 0 until rowObject.length()) {
                    when (val item = rowObject.get(index)) {
                        is JSONObject -> {
                            if (index == 0) {
                                rotation = item.optDouble("r", rotation.toDouble()).toFloat()
                                rx = item.optDouble("rx", rx.toDouble()).toFloat()
                                ry = item.optDouble("ry", ry.toDouble()).toFloat()
                                x = rx
                                if (item.has("rx") || item.has("ry")) y = ry
                            } else if (item.has("r") || item.has("rx") || item.has("ry")) {
                                throw JSONException("回転属性は行の先頭に指定してください")
                            }
                            x += item.optDouble("x", 0.0).toFloat()
                            y += item.optDouble("y", 0.0).toFloat()
                            w = item.optDouble("w", w.toDouble()).toFloat()
                            h = item.optDouble("h", h.toDouble()).toFloat()
                            x2 = item.optDouble("x2", x2.toDouble()).toFloat()
                            y2 = item.optDouble("y2", y2.toDouble()).toFloat()
                            w2 = item.optDouble("w2", w2.toDouble()).toFloat()
                            h2 = item.optDouble("h2", h2.toDouble()).toFloat()
                            if (item.has("a")) align = item.optInt("a", 4)
                            if (item.has("c")) color = parseColor(item.optString("c"), color)
                            if (item.has("d")) decal = item.optBoolean("d")
                            if (item.has("g")) ghost = item.optBoolean("g")
                        }
                        is String -> {
                            if (w <= 0 || h <= 0 || w > 30 || h > 30) throw JSONException("キーの大きさが不正です")
                            val key = Key()
                            key.index = keys.size
                            key.x = x; key.y = y; key.w = w; key.h = h
                            key.x2 = x2; key.y2 = y2
                            key.w2 = if (w2 == 0f) w else w2
                            key.h2 = if (h2 == 0f) h else h2
                            key.rx = rx; key.ry = ry; key.rotation = rotation
                            key.color = color; key.decal = decal; key.ghost = ghost
                            key.labels = reorderLabels(item.split("\n").toTypedArray(), align)
                            keys.add(key)
                            if (keys.size > 300) throw JSONException("キー数が多すぎます（最大300）")
                            x += w
                            w = 1f; h = 1f
                            x2 = 0f; y2 = 0f; w2 = 0f; h2 = 0f
                            decal = false
                        }
                        else -> throw JSONException("行に文字列以外のキーがあります")
                    }
                }
                y += 1f
            }
            if (keys.isEmpty()) throw JSONException("キーがありません")
            return KleLayout(name, keys)
        }

        private fun reorderLabels(raw: Array<String>, align: Int): Array<String> {
            val map = arrayOf(
                intArrayOf(0,6,2,8,9,11,3,5,1,4,7,10),
                intArrayOf(1,7,-1,-1,9,11,4,-1,-1,-1,-1,10),
                intArrayOf(3,-1,5,-1,9,11,-1,-1,4,-1,-1,10),
                intArrayOf(4,-1,-1,-1,9,11,-1,-1,-1,-1,-1,10),
                intArrayOf(0,6,2,8,10,-1,3,5,1,4,7,-1),
                intArrayOf(1,7,-1,-1,10,-1,4,-1,-1,-1,-1,-1),
                intArrayOf(3,-1,5,-1,10,-1,-1,-1,4,-1,-1,-1),
                intArrayOf(4,-1,-1,-1,10,-1,-1,-1,-1,-1,-1,-1)
            )
            val labels = Array(12) { "" }
            val mapping = map[align.coerceIn(0, 7)]
            for (index in 0 until minOf(raw.size, 12)) {
                val at = mapping[index]
                if (at >= 0) labels[at] = raw[index].replace(Regex("<[^>]*>"), "").trim()
            }
            return labels
        }

        private fun parseColor(raw: String, fallback: Int): Int =
            try { Color.parseColor(raw) } catch (_: IllegalArgumentException) { fallback }
    }
}
