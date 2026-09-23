package jp.liki.pockethid

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class SavedLayout(
    val name: String,
    val kleJson: String,
    val overrides: String,
    val layerOverrides: String,
    val viewState: KleKeyboardView.ViewState
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("kle_json", kleJson)
        put("overrides", overrides)
        put("layer_overrides", layerOverrides)
        put("zoom", viewState.zoom.toDouble())
        put("pan_x", viewState.panX.toDouble())
        put("pan_y", viewState.panY.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): SavedLayout? {
            val name = json.optString("name").trim()
            val kleJson = json.optString("kle_json")
            if (name.isEmpty() || kleJson.isEmpty()) return null
            val zoom = json.optDouble("zoom", 1.0).toFloat()
            val panX = json.optDouble("pan_x", 0.0).toFloat()
            val panY = json.optDouble("pan_y", 0.0).toFloat()
            if (!zoom.isFinite() || zoom <= 0f || !panX.isFinite() || !panY.isFinite()) return null
            return SavedLayout(name, kleJson, json.optString("overrides", "{}"),
                json.optString("layer_overrides", "{}"), KleKeyboardView.ViewState(zoom, panX, panY))
        }
    }
}

internal class SavedLayoutStore(private val preferences: SharedPreferences) {
    companion object { private const val KEY = "saved_layouts" }

    fun all(): List<SavedLayout> = try {
        val stored = JSONArray(preferences.getString(KEY, "[]") ?: "[]")
        (0 until stored.length()).mapNotNull { index ->
            stored.optJSONObject(index)?.let(SavedLayout::fromJson)
        }
    } catch (_: Exception) { emptyList() }

    fun save(layout: SavedLayout) {
        val entries = all().toMutableList()
        val index = entries.indexOfFirst { it.name.equals(layout.name, ignoreCase = true) }
        if (index >= 0) entries[index] = layout else entries.add(layout)
        write(entries)
    }

    fun delete(name: String) {
        write(all().filterNot { it.name.equals(name, ignoreCase = true) })
    }

    private fun write(entries: List<SavedLayout>) {
        val json = JSONArray()
        entries.forEach { json.put(it.toJson()) }
        preferences.edit().putString(KEY, json.toString()).apply()
    }
}
