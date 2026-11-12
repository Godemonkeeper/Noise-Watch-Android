package com.noisewatch.android.storage

import android.content.Context
import com.noisewatch.android.model.AppLanguage
import com.noisewatch.android.model.FrequencyBand
import com.noisewatch.android.model.MonitorSettings

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("noise_watch_settings", Context.MODE_PRIVATE)

    fun load(): MonitorSettings {
        val hasDevice = prefs.contains(KEY_DEVICE_ID)
        val deviceId = if (hasDevice) prefs.getInt(KEY_DEVICE_ID, -1).takeIf { it >= 0 } else null

        val bandNames = prefs.getStringSet(KEY_BANDS, setOf(FrequencyBand.LOW.name))
            ?: setOf(FrequencyBand.LOW.name)
        val bands = bandNames.mapNotNull { name ->
            runCatching { FrequencyBand.valueOf(name) }.getOrNull()
        }.toSet().ifEmpty { setOf(FrequencyBand.LOW) }

        val langName = prefs.getString(KEY_LANGUAGE, AppLanguage.ZH.name) ?: AppLanguage.ZH.name
        val language = runCatching { AppLanguage.valueOf(langName) }.getOrDefault(AppLanguage.ZH)

        return MonitorSettings(
            selectedDeviceId = deviceId,
            selectedBands = bands,
            thresholdDb = prefs.getFloat(KEY_THRESHOLD_DB, -35f).coerceIn(-80f, 0f),
            impactCount = prefs.getInt(KEY_IMPACT_COUNT, 3).coerceIn(1, 20),
            timeWindowSec = prefs.getInt(KEY_TIME_WINDOW_SEC, 10).coerceIn(1, 120),
            cooldownSec = prefs.getInt(KEY_COOLDOWN_SEC, 30).coerceIn(0, 300),
            customAudioUri = prefs.getString(KEY_CUSTOM_AUDIO_URI, null),
            customAudioName = prefs.getString(KEY_CUSTOM_AUDIO_NAME, null),
            language = language
        )
    }

    fun save(settings: MonitorSettings) {
        prefs.edit().apply {
            if (settings.selectedDeviceId != null) {
                putInt(KEY_DEVICE_ID, settings.selectedDeviceId)
            } else {
                remove(KEY_DEVICE_ID)
            }
            putStringSet(KEY_BANDS, settings.selectedBands.map { it.name }.toSet())
            putFloat(KEY_THRESHOLD_DB, settings.thresholdDb)
            putInt(KEY_IMPACT_COUNT, settings.impactCount)
            putInt(KEY_TIME_WINDOW_SEC, settings.timeWindowSec)
            putInt(KEY_COOLDOWN_SEC, settings.cooldownSec)
            putString(KEY_CUSTOM_AUDIO_URI, settings.customAudioUri)
            putString(KEY_CUSTOM_AUDIO_NAME, settings.customAudioName)
            putString(KEY_LANGUAGE, settings.language.name)
            apply()
        }
    }

    companion object {
        private const val KEY_DEVICE_ID = "selected_device_id"
        private const val KEY_BANDS = "selected_bands"
        private const val KEY_THRESHOLD_DB = "threshold_db"
        private const val KEY_IMPACT_COUNT = "impact_count"
        private const val KEY_TIME_WINDOW_SEC = "time_window_sec"
        private const val KEY_COOLDOWN_SEC = "cooldown_sec"
        private const val KEY_CUSTOM_AUDIO_URI = "custom_audio_uri"
        private const val KEY_CUSTOM_AUDIO_NAME = "custom_audio_name"
        private const val KEY_LANGUAGE = "language"
    }
}
