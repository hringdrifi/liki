package jp.liki.pockethid

import android.content.Context
import org.json.JSONObject

/** Expo layouts start with fresh preferences; earlier Android settings are not imported. */
internal class LayoutRepository(context: Context) {
    private val assets = context.assets
    private val preferences = context.getSharedPreferences("expo_layout", Context.MODE_PRIVATE)

    fun initial(): Map<String, Any?> {
        val json = preferences.getString("kle_json", null) ?: readAsset("default-kle.json")
        KleLayout.parse(json)
        return mapOf(
            "json" to json,
            "overrides" to (preferences.getString("overrides", "{}") ?: "{}"),
            "layers" to (preferences.getString("layer_overrides", "{}") ?: "{}"),
            "selected" to preferences.getString("selected_saved_layout_name", null)
        )
    }

    fun inferred(json: String): String {
        val parsed = KleLayout.parse(json)
        val normalized = normalize(json)
        val bundled = when (normalized) {
            normalize(readAsset("ghosted-trackpad.json")) -> BundledBindings.trackpad
            normalize(readAsset("default-kle.json")) -> BundledBindings.keyboard
            else -> null
        }
        val bindings = JSONObject()
        if (bundled != null && bundled.size == parsed.keys.size) {
            BundledBindings.into(bindings, bundled)
        } else {
            for (key in parsed.keys) KeyBinding.fromKleLabels(key)?.let {
                bindings.put(key.index.toString(), it.name)
            }
        }
        return bindings.toString()
    }

    private fun normalize(json: String) = json.replace("\r\n", "\n").trim()
    private fun readAsset(name: String) = assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
