package com.noisewatch.android.model

enum class FrequencyBand(val minHz: Double, val maxHz: Double) {
    LOW(20.0, 250.0),
    MID(250.0, 2000.0),
    HIGH(2000.0, 8000.0)
}

enum class AppLanguage {
    ZH, EN
}

data class AudioInputDeviceInfo(
    val id: Int?,
    val name: String,
    val typeLabel: String
)

data class MonitorSettings(
    val selectedDeviceId: Int? = null,
    val selectedBands: Set<FrequencyBand> = setOf(FrequencyBand.LOW),
    val thresholdDb: Float = -35f,
    val impactCount: Int = 3,
    val timeWindowSec: Int = 10,
    val cooldownSec: Int = 30,
    val customAudioUri: String? = null,
    val customAudioName: String? = null,
    val language: AppLanguage = AppLanguage.ZH
)

data class BandLevels(
    val lowDb: Float = -100f,
    val midDb: Float = -100f,
    val highDb: Float = -100f,
    val overallDb: Float = -100f
) {
    fun effectiveDb(selectedBands: Set<FrequencyBand>): Float {
        if (selectedBands.isEmpty()) return overallDb
        var maxVal = -100f
        for (band in selectedBands) {
            val v = when (band) {
                FrequencyBand.LOW -> lowDb
                FrequencyBand.MID -> midDb
                FrequencyBand.HIGH -> highDb
            }
            if (v > maxVal) maxVal = v
        }
        return maxVal
    }
}

data class TriggerEvent(
    val id: Long,
    val timestampMs: Long,
    val effectiveDb: Float,
    val lowDb: Float,
    val midDb: Float,
    val highDb: Float,
    val thresholdDb: Float,
    val selectedBands: Set<FrequencyBand>,
    val impactCount: Int,
    val timeWindowSec: Int,
    val cooldownSec: Int,
    val alertMode: String
)

data class MonitorState(
    val isMonitoring: Boolean = false,
    val isAlertPlaying: Boolean = false,
    val levels: BandLevels = BandLevels(),
    val effectiveDb: Float = -100f,
    val activeHitsInWindow: Int = 0,
    val cooldownRemainingSec: Int = 0,
    val recentEvents: List<TriggerEvent> = emptyList(),
    val totalTriggerCount: Int = 0,
    val jsonPath: String = "",
    val csvPath: String = "",
    val lastCsvExportMs: Long? = null,
    val errorMessage: String? = null
)
