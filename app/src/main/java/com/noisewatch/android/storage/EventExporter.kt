package com.noisewatch.android.storage

import android.content.Context
import com.noisewatch.android.model.MonitorSettings
import com.noisewatch.android.model.MonitorState
import com.noisewatch.android.model.TriggerEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Handles real-time JSON snapshot writing (events/latest-events.json)
 * and periodic 5-minute CSV exporting (exports/latest-events.csv).
 */
class EventExporter(context: Context) {

    private val baseDir: File = context.getExternalFilesDir(null) ?: context.filesDir
    private val eventsDir = File(baseDir, "events").apply { mkdirs() }
    private val exportsDir = File(baseDir, "exports").apply { mkdirs() }

    val jsonFile = File(eventsDir, "latest-events.json")
    val csvFile = File(exportsDir, "latest-events.csv")

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Synchronized
    fun writeJsonSnapshot(state: MonitorState, settings: MonitorSettings) {
        val root = JSONObject().apply {
            put("updatedAt", formatIso(System.currentTimeMillis()))
            put("isMonitoring", state.isMonitoring)
            put("effectiveDb", state.effectiveDb.toDouble())
            put("levels", JSONObject().apply {
                put("lowDb", state.levels.lowDb.toDouble())
                put("midDb", state.levels.midDb.toDouble())
                put("highDb", state.levels.highDb.toDouble())
                put("overallDb", state.levels.overallDb.toDouble())
            })
            put("settings", JSONObject().apply {
                put("selectedBands", JSONArray(settings.selectedBands.map { it.name }))
                put("thresholdDb", settings.thresholdDb.toDouble())
                put("impactCount", settings.impactCount)
                put("timeWindowSec", settings.timeWindowSec)
                put("cooldownSec", settings.cooldownSec)
            })
            put("totalTriggerCount", state.totalTriggerCount)
            put("events", JSONArray().apply {
                for (event in state.recentEvents) {
                    put(JSONObject().apply {
                        put("id", event.id)
                        put("timestamp", formatIso(event.timestampMs))
                        put("effectiveDb", event.effectiveDb.toDouble())
                        put("lowDb", event.lowDb.toDouble())
                        put("midDb", event.midDb.toDouble())
                        put("highDb", event.highDb.toDouble())
                        put("thresholdDb", event.thresholdDb.toDouble())
                        put("selectedBands", event.selectedBands.joinToString("+") { it.name })
                        put("impactCount", event.impactCount)
                        put("timeWindowSec", event.timeWindowSec)
                        put("cooldownSec", event.cooldownSec)
                        put("alertMode", event.alertMode)
                    })
                }
            })
        }
        jsonFile.writeText(root.toString(2), Charsets.UTF_8)
    }

    @Synchronized
    fun writeCsvExport(events: List<TriggerEvent>): Long {
        val sb = StringBuilder()
        // UTF-8 BOM for Excel compatibility
        sb.append('\uFEFF')
        sb.append("id,timestamp_iso,effective_db,low_db,mid_db,high_db,threshold_db,selected_bands,impact_count,time_window_sec,cooldown_sec,alert_mode\n")
        for (e in events) {
            sb.append(e.id).append(',')
                .append(formatIso(e.timestampMs)).append(',')
                .append(String.format(Locale.US, "%.2f", e.effectiveDb)).append(',')
                .append(String.format(Locale.US, "%.2f", e.lowDb)).append(',')
                .append(String.format(Locale.US, "%.2f", e.midDb)).append(',')
                .append(String.format(Locale.US, "%.2f", e.highDb)).append(',')
                .append(String.format(Locale.US, "%.2f", e.thresholdDb)).append(',')
                .append('"').append(e.selectedBands.joinToString("+") { it.name }).append('"').append(',')
                .append(e.impactCount).append(',')
                .append(e.timeWindowSec).append(',')
                .append(e.cooldownSec).append(',')
                .append('"').append(e.alertMode.replace("\"", "\"\"")).append('"')
                .append('\n')
        }
        csvFile.writeText(sb.toString(), Charsets.UTF_8)
        return System.currentTimeMillis()
    }

    private fun formatIso(ms: Long): String = synchronized(isoFormat) {
        isoFormat.format(Date(ms))
    }
}
