package jp.liki.pockethid

import android.content.Context
import org.json.JSONObject
import java.io.InputStream

/** Keeps main's stored KLE/binding migration and bundled defaults unchanged. */
internal class LayoutRepository(context: Context) {
    private val assets = context.assets
    private val preferences = context.getSharedPreferences("layout", Context.MODE_PRIVATE)
    private val savedLayouts = SavedLayoutStore(preferences)
    private val CURRENT_BINDING_SCHEMA_VERSION = 1
    private var selectedSavedLayoutName: String? = null
    private lateinit var currentKleJson: String
    private lateinit var layout: KleLayout
    private var overrides = JSONObject()
    private var layerOverrides = JSONObject()

    fun initial(): Map<String, Any?> {
        loadLayout()
        migrateBindingSchema()
        return mapOf("json" to currentKleJson, "overrides" to overrides.toString(),
            "layers" to layerOverrides.toString(), "selected" to selectedSavedLayoutName)
    }

    fun inferred(json: String): String = materializeBindings(json, JSONObject()).toString()
    private fun readText(input: InputStream): String = input.bufferedReader(Charsets.UTF_8).readText()
    private fun loadLayout() {
        var json = preferences.getString("kle_json", null)
        try {
            selectedSavedLayoutName = preferences.getString("selected_saved_layout_name", null)
            if (json == null) json = assets.open("default-kle.json").use(::readText)
            else if (selectedSavedLayoutName == null) {
                val upgraded = upgradeBundledLayout(json)
                if (upgraded != json) {
                    json = upgraded
                    preferences.edit().putString("kle_json", upgraded).apply()
                }
            }
            currentKleJson = requireNotNull(json)
            layout = KleLayout.parse(currentKleJson)
            overrides = JSONObject(preferences.getString("overrides", "{}") ?: "{}")
            layerOverrides = JSONObject(preferences.getString("layer_overrides", "{}") ?: "{}")
        } catch (_: Exception) {
            try {
                currentKleJson = assets.open("default-kle.json").use(::readText)
                layout = KleLayout.parse(currentKleJson)
                overrides = JSONObject()
                layerOverrides = JSONObject()
                selectedSavedLayoutName = null
            } catch (error: Exception) { throw IllegalStateException("標準レイアウトを読めません", error) }
        }
    }

    private fun migrateBindingSchema() {
        var version = preferences.getInt("binding_schema_version", 0)
        while (version < CURRENT_BINDING_SCHEMA_VERSION) {
            when (version) {
                0 -> migrateBindingsFromV0()
                else -> error("未対応のキー割り当てデータ形式: $version")
            }
            version++
            preferences.edit().putString("overrides", overrides.toString())
                .putInt("binding_schema_version", version).apply()
        }
    }

    private fun migrateBindingsFromV0() {
        overrides = materializeBindings(currentKleJson, overrides)
        for (saved in savedLayouts.all()) {
            try {
                val explicit = materializeBindings(saved.kleJson, JSONObject(saved.overrides))
                if (explicit.toString() != saved.overrides)
                    savedLayouts.save(saved.copy(overrides = explicit.toString()))
            } catch (_: Exception) { /* Leave malformed saved layouts untouched. */ }
        }
    }

    private fun materializeBindings(json: String, mappings: JSONObject): JSONObject {
        val parsed = KleLayout.parse(json)
        val normalized = json.replace("\r\n", "\n").trim()
        val bundled = when {
            listOf("ghosted-trackpad.json", "labeled-ghosted-trackpad.json",
                "legacy-ghosted-trackpad.json").any { assetMatches(it, normalized) } -> BundledBindings.trackpad
            listOf("default-kle.json", "legacy-default-kle.json")
                .any { assetMatches(it, normalized) } -> BundledBindings.keyboard
            else -> null
        }
        if (bundled != null && bundled.size == parsed.keys.size)
            return BundledBindings.into(mappings, bundled)
        for (key in parsed.keys) {
            val index = key.index.toString()
            if (!mappings.has(index)) KeyBinding.fromKleLabels(key)?.let {
                mappings.put(index, it.name)
            }
        }
        return mappings
    }

    private fun assetMatches(asset: String, normalized: String): Boolean =
        assets.open(asset).use(::readText).replace("\r\n", "\n").trim() == normalized

    private fun upgradeBundledLayout(saved: String): String {
        val normalized = saved.replace("\r\n", "\n").trim()
        for ((oldAsset, newAsset) in listOf(
            "labeled-ghosted-trackpad.json" to "ghosted-trackpad.json",
            "legacy-ghosted-trackpad.json" to "ghosted-trackpad.json",
            "legacy-default-kle.json" to "default-kle.json")) {
            val old = assets.open(oldAsset).use(::readText).replace("\r\n", "\n").trim()
            if (normalized == old) return assets.open(newAsset).use(::readText)
        }
        return saved
    }


}
