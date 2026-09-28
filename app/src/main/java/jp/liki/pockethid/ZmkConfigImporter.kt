package jp.liki.pockethid

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Reads a public zmk-config snapshot. Call from a background thread. */
internal object ZmkConfigImporter {
    data class Profile(
        val sourceUrl: String,
        val name: String,
        val layoutJson: String,
        val layerNames: List<String>,
        val layers: List<List<String>>,
        val leftPositions: Set<Int>
    ) {
        fun toJson(): String = JSONObject().put("url", sourceUrl).put("name", name)
            .put("layout", layoutJson).put("layerNames", JSONArray(layerNames))
            .put("layers", JSONArray(layers.map(::JSONArray)))
            .put("left", JSONArray(leftPositions.sorted())).toString()
    }

    fun fromJson(json: String): Profile {
        val data = JSONObject(json)
        val layerNames = data.getJSONArray("layerNames")
        val layers = data.getJSONArray("layers")
        val left = data.getJSONArray("left")
        return Profile(data.getString("url"), data.getString("name"), data.getString("layout"),
            (0 until layerNames.length()).map(layerNames::getString),
            (0 until layers.length()).map { index ->
                val row = layers.getJSONArray(index)
                (0 until row.length()).map(row::getString)
            }, (0 until left.length()).map(left::getInt).toSet())
    }

    fun load(url: String): Profile {
        val uri = URI(url.trim())
        require(uri.scheme == "https" && uri.host.equals("github.com", true)) { "GitHub の https URL を入力してください" }
        val parts = uri.path.trim('/').split('/').filter { it.isNotEmpty() }
        require(parts.size >= 2 && parts[0].matches(Regex("[A-Za-z0-9_.-]+")) &&
            parts[1].matches(Regex("[A-Za-z0-9_.-]+"))) { "リポジトリ URL が不正です" }
        val owner = parts[0]; val repo = parts[1]
        val branch = if (parts.size == 2) null else {
            require(parts.size >= 4 && parts[2] == "tree") { "リポジトリまたはブランチの URL を入力してください" }
            parts.drop(3).joinToString("/")
        }
        val base = "https://api.github.com/repos/$owner/$repo/contents/config"
        val query = branch?.let { "?ref=${encode(it)}" } ?: ""
        val files = JSONArray(fetch(base + query))
        val names = (0 until files.length()).map { files.getJSONObject(it).getString("name") }
        val keymaps = names.filter { it.endsWith(".keymap") &&
            !it.endsWith("_left.keymap") && !it.endsWith("_right.keymap") }
        require(keymaps.size == 1) { "共通 .keymap を一意に選べません: ${keymaps.joinToString()}" }
        val keyboardJson = keymaps.single().removeSuffix(".keymap") + ".json"
        val layoutFile = if (keyboardJson in names) keyboardJson else if ("info.json" in names) "info.json" else {
            val jsons = names.filter { it.endsWith(".json") }
            require(jsons.size == 1) { "config/info.json または一つの配置 JSON が必要です" }
            jsons.single()
        }
        val info = JSONObject(fetch("$base/${encode(layoutFile)}$query"))
        val layouts = info.getJSONObject("layouts")
        val layoutName = if (layouts.has("LAYOUT")) "LAYOUT" else layouts.keys().asSequence().firstOrNull()
            ?: error("配置がありません")
        val keys = layouts.getJSONObject(layoutName).getJSONArray("layout")
        require(keys.length() in 1..128) { "キー数は1〜128にしてください" }
        val name = info.optString("keyboard_name", repo)
        val xs = (0 until keys.length()).map { keys.getJSONObject(it).getDouble("x") }
        val center = (xs.minOrNull()!! + xs.maxOrNull()!!) / 2
        val left = xs.indices.filter { xs[it] < center }.toSet()
        val keymap = fetch("$base/${encode(keymaps.single())}$query")
        val defines = Regex("(?m)^\\s*#define\\s+(\\w+)\\s+(\\d+)\\b").findAll(keymap)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val clean = keymap.replace(Regex("/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*"), "")
        val sections = Regex("(?s)\\b([A-Za-z_]\\w*)\\s*\\{([^{}]*)};").findAll(clean)
            .mapNotNull { match ->
                val body = match.groupValues[2]
                val bindings = Regex("(?s)\\bbindings\\s*=\\s*<([^>]*)>\\s*;").find(body)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val display = Regex("\\bdisplay-name\\s*=\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
                    ?: match.groupValues[1]
                display to parseBindings(bindings, defines)
            }.toList()
        require(sections.isNotEmpty()) { "bindings を含むレイヤーがありません（#include やマクロ展開が必要な構成は未対応です）" }
        require(sections.all { it.second.size == keys.length() }) {
            "配置 ${keys.length()} キーと bindings の個数が一致しません"
        }
        return Profile(url.trim(), name, info.toString(), sections.map { it.first },
            sections.map { it.second }, left)
    }

    private fun parseBindings(source: String, defines: Map<String, String>): List<String> {
        val entries = Regex("&[A-Za-z_][A-Za-z_0-9]*(?:\\s+(?!&)[^&\\s]+)*").findAll(source)
            .map { it.value.trim().split(Regex("\\s+")) }.toList()
        return entries.map { parts ->
            when (parts[0]) {
                "&trans" -> "TRANS"
                "&none", "&studio_unlock" -> "NONE"
                "&mo" -> "MO${(defines[parts.getOrNull(1)] ?: parts.getOrNull(1)).orEmpty().toIntOrNull()
                    ?: error("&mo のレイヤー番号が不明です")}"
                "&kp" -> parts.getOrNull(1)?.let { key ->
                    if (key.matches(Regex("LC\\([A-Z]\\)"))) "LC_${key[3]}" else key
                } ?: error("&kp のキーコードがありません")
                "&mkp" -> when (parts.getOrNull(1)) {
                    "LCLK" -> "MKP_L"; "MCLK" -> "MKP_M"; "RCLK" -> "MKP_R"
                    else -> error("未対応のマウスボタン: ${parts.joinToString(" ")}")
                }
                "&msc" -> when (parts.getOrNull(1)) {
                    "SCRL_UP" -> "MSC_UP"; "SCRL_DOWN" -> "MSC_DOWN"
                    else -> error("未対応のスクロール: ${parts.joinToString(" ")}")
                }
                else -> error("未対応の動作: ${parts.joinToString(" ")}")
            }
        }
    }

    private fun fetch(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("Accept", "application/vnd.github.raw+json")
        connection.setRequestProperty("User-Agent", "Liki-ZMK-config-import")
        try {
            if (connection.responseCode != 200) error("GitHubから読み込めません (HTTP ${connection.responseCode})")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1024 * 1024) { "ファイルが1MBを超えています" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
