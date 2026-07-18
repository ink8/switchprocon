package com.ink8.switchprocon.config

import android.content.Context
import android.graphics.Color
import com.ink8.switchprocon.input.MacroEngine
import org.json.JSONObject

/**
 * Persisted customization, mirroring what the Manba One exposes on its built-in screen:
 * accent (RGB light) color, turbo speed, stick sensitivity, haptics — plus three named
 * profile slots that snapshot all of it *including* the M1–M4 macros, so each game can
 * have its own setup.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("switchprocon", Context.MODE_PRIVATE)

    var accent: Int
        get() = prefs.getInt(KEY_ACCENT, DEFAULT_ACCENT)
        set(v) = prefs.edit().putInt(KEY_ACCENT, v).apply()

    /** Full on+off turbo cycle in ms (66 ≈ 15 presses/sec). */
    var turboMs: Long
        get() = prefs.getLong(KEY_TURBO, 66L)
        set(v) = prefs.edit().putLong(KEY_TURBO, v).apply()

    /** Stick output multiplier, 0.5–1.5. */
    var sensitivity: Float
        get() = prefs.getFloat(KEY_SENS, 1f)
        set(v) = prefs.edit().putFloat(KEY_SENS, v).apply()

    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(v) = prefs.edit().putBoolean(KEY_HAPTICS, v).apply()

    var activeProfile: Int
        get() = prefs.getInt(KEY_ACTIVE, 0)
        set(v) = prefs.edit().putInt(KEY_ACTIVE, v).apply()

    /** Snapshot everything (settings + macro slots) into profile [index] (0–2). */
    fun saveProfile(index: Int, engine: MacroEngine) {
        val json = JSONObject()
            .put("accent", accent)
            .put("turbo", turboMs)
            .put("sens", sensitivity.toDouble())
            .put("haptics", haptics)
            .put("slots", engine.slotsToJson())
        prefs.edit().putString(profileKey(index), json.toString()).apply()
    }

    fun hasProfile(index: Int): Boolean = prefs.contains(profileKey(index))

    /** Restore profile [index] into the live settings + engine. @return false if empty. */
    fun loadProfile(index: Int, engine: MacroEngine): Boolean {
        val raw = prefs.getString(profileKey(index), null) ?: return false
        return try {
            val json = JSONObject(raw)
            accent = json.optInt("accent", DEFAULT_ACCENT)
            turboMs = json.optLong("turbo", 66L)
            sensitivity = json.optDouble("sens", 1.0).toFloat()
            haptics = json.optBoolean("haptics", true)
            json.optJSONArray("slots")?.let { engine.loadSlotsFromJson(it) }
            engine.turboIntervalMs = turboMs
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun profileKey(index: Int) = "$KEY_PROFILE$index"

    companion object {
        val DEFAULT_ACCENT = Color.parseColor("#00B4C8")

        /** The "RGB light" palette offered in settings. */
        val ACCENT_CHOICES = intArrayOf(
            Color.parseColor("#00B4C8"), // teal
            Color.parseColor("#A24BFF"), // purple
            Color.parseColor("#FF3B5C"), // red
            Color.parseColor("#2BE879"), // green
            Color.parseColor("#FF9F1C"), // orange
            Color.parseColor("#3B82F6"), // blue
        )

        private const val KEY_ACCENT = "accent"
        private const val KEY_TURBO = "turboMs"
        private const val KEY_SENS = "sensitivity"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_ACTIVE = "activeProfile"
        private const val KEY_PROFILE = "profile_"
    }
}
