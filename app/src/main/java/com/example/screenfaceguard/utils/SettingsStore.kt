package com.example.screenfaceguard.utils

import android.content.Context
import com.example.screenfaceguard.model.OverlayStyle
import com.example.screenfaceguard.model.PerformanceMode
import com.example.screenfaceguard.model.ProtectionSettings

/** Stores settings locally in SharedPreferences. */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("face_guard_settings", Context.MODE_PRIVATE)

    fun load(): ProtectionSettings {
        val d = ProtectionSettings()
        val fps = prefs.getInt("fps", d.fps)
        return ProtectionSettings(
            femaleThreshold = prefs.getFloat("female_threshold", d.femaleThreshold).coerceIn(0.5f, 0.99f),
            maleThreshold = prefs.getFloat("male_threshold", d.maleThreshold).coerceIn(0.5f, 0.99f),
            blockUncertain = prefs.getBoolean("block_uncertain", d.blockUncertain),
            overlayStyle = enumOr(prefs.getString("overlay_style", null), d.overlayStyle),
            paddingPercent = prefs.getInt("padding_percent", d.paddingPercent).coerceIn(0, 40),
            fps = if (fps == 5 || fps == 10 || fps == 15) fps else d.fps,
            performanceMode = enumOr(prefs.getString("performance_mode", null), d.performanceMode),
            holdMillis = prefs.getLong("hold_millis", d.holdMillis).coerceIn(0L, 2000L)
        )
    }

    fun save(s: ProtectionSettings) {
        prefs.edit()
            .putFloat("female_threshold", s.femaleThreshold)
            .putFloat("male_threshold", s.maleThreshold)
            .putBoolean("block_uncertain", s.blockUncertain)
            .putString("overlay_style", s.overlayStyle.name)
            .putInt("padding_percent", s.paddingPercent)
            .putInt("fps", s.fps)
            .putString("performance_mode", s.performanceMode.name)
            .putLong("hold_millis", s.holdMillis)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
