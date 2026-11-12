package com.noisewatch.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noisewatch.android.model.AppLanguage
import com.noisewatch.android.model.AudioInputDeviceInfo
import com.noisewatch.android.model.FrequencyBand
import com.noisewatch.android.model.MonitorSettings
import com.noisewatch.android.model.MonitorState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoiseWatchScreen(
    state: MonitorState,
    settings: MonitorSettings,
    devices: List<AudioInputDeviceInfo>,
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    onUpdateSettings: (MonitorSettings) -> Unit,
    onRefreshDevices: () -> Unit,
    onPickAudio: () -> Unit,
    onClearAudio: () -> Unit,
    onTestAlert: () -> Unit,
    onStopAlert: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onExportCsvNow: () -> Unit,
    onShareCsv: () -> Unit
) {
    val s = stringsFor(settings.language)
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = s.appTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = s.appSubtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Surface(
                        color = if (state.isMonitoring) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            text = if (state.isMonitoring) s.statusMonitoring else s.statusReady,
                            color = if (state.isMonitoring) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    IconButton(onClick = {
                        val nextLang = if (settings.language == AppLanguage.ZH) AppLanguage.EN else AppLanguage.ZH
                        onUpdateSettings(settings.copy(language = nextLang))
                    }) {
                        Icon(Icons.Default.Translate, contentDescription = "Language")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Button(
                        onClick = {
                            if (state.isMonitoring) onStopMonitoring()
                            else if (hasMicPermission) onStartMonitoring()
                            else onRequestPermission()
                        },
                        colors = if (state.isMonitoring) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isMonitoring) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (state.isMonitoring) s.stopMonitoring else s.startMonitoring,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasMicPermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = s.permissionTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = s.permissionDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRequestPermission) {
                            Text(s.grantPermission)
                        }
                    }
                }
            }

            // 1. Real-time dBFS Level Card
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = s.levelCardTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text(
                                text = s.effectiveLevelLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f dBFS", state.effectiveDb),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (state.effectiveDb >= settings.thresholdDb) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${s.hitsInWindow}: ${state.activeHitsInWindow} / ${settings.impactCount}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = "${s.cooldownRemaining}: ${state.cooldownRemainingSec}${s.secUnit}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LevelMeterBar(
                        levelDb = state.effectiveDb,
                        thresholdDb = settings.thresholdDb
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        BandReadout(label = s.bandLow, db = state.levels.lowDb)
                        BandReadout(label = s.bandMid, db = state.levels.midDb)
                        BandReadout(label = s.bandHigh, db = state.levels.highDb)
                    }
                }
            }

            // 2. Input Device Selection
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = s.inputDeviceTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = onRefreshDevices) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.width(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(s.refreshDevices)
                        }
                    }

                    var expanded by remember { mutableStateOf(false) }
                    val selectedDevice = devices.firstOrNull { it.id == settings.selectedDeviceId }
                    val displayLabel = selectedDevice?.let { "${it.name} (${it.typeLabel})" } ?: s.defaultMicLabel

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = displayLabel,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(s.defaultMicLabel) },
                                onClick = {
                                    onUpdateSettings(settings.copy(selectedDeviceId = null))
                                    expanded = false
                                }
                            )
                            devices.forEach { dev ->
                                DropdownMenuItem(
                                    text = { Text("${dev.name} (${dev.typeLabel})") },
                                    onClick = {
                                        onUpdateSettings(settings.copy(selectedDeviceId = dev.id))
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 3. Frequency Band Multi-Selection
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = s.bandSelectionTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = s.bandSelectionHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            FrequencyBand.LOW to s.bandLow,
                            FrequencyBand.MID to s.bandMid,
                            FrequencyBand.HIGH to s.bandHigh
                        ).forEach { (band, label) ->
                            val selected = band in settings.selectedBands
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = if (selected) {
                                        if (settings.selectedBands.size > 1) settings.selectedBands - band else settings.selectedBands
                                    } else {
                                        settings.selectedBands + band
                                    }
                                    onUpdateSettings(settings.copy(selectedBands = next))
                                },
                                label = { Text(label) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                        }
                    }
                }
            }

            // 4. Detection Rules
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = s.rulesTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    RuleSlider(
                        label = s.thresholdLabel,
                        valueText = "${settings.thresholdDb.roundToInt()} dBFS",
                        value = settings.thresholdDb,
                        valueRange = -80f..0f,
                        onValueChange = { onUpdateSettings(settings.copy(thresholdDb = it.roundToInt().toFloat())) }
                    )

                    RuleSlider(
                        label = s.impactCountLabel,
                        valueText = "${settings.impactCount} ${s.timesUnit}",
                        value = settings.impactCount.toFloat(),
                        valueRange = 1f..15f,
                        onValueChange = { onUpdateSettings(settings.copy(impactCount = it.roundToInt())) }
                    )

                    RuleSlider(
                        label = s.timeWindowLabel,
                        valueText = "${settings.timeWindowSec} ${s.secUnit}",
                        value = settings.timeWindowSec.toFloat(),
                        valueRange = 1f..60f,
                        onValueChange = { onUpdateSettings(settings.copy(timeWindowSec = it.roundToInt())) }
                    )

                    RuleSlider(
                        label = s.cooldownLabel,
                        valueText = "${settings.cooldownSec} ${s.secUnit}",
                        value = settings.cooldownSec.toFloat(),
                        valueRange = 0f..180f,
                        onValueChange = { onUpdateSettings(settings.copy(cooldownSec = it.roundToInt())) }
                    )
                }
            }

            // 5. Local Alert Audio & Stop Control
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = s.alertSectionTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${s.currentAlertAudio}: ${settings.customAudioName ?: s.builtInDefaultAudio}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onPickAudio) {
                            Icon(Icons.Default.AudioFile, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(s.chooseAudioFile)
                        }
                        if (settings.customAudioUri != null) {
                            OutlinedButton(onClick = onClearAudio) {
                                Text(s.clearAudioFile)
                            }
                        }
                        OutlinedButton(onClick = onTestAlert) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(s.testAlert)
                        }
                        Button(
                            onClick = onStopAlert,
                            enabled = state.isAlertPlaying,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(s.stopAlert)
                        }
                    }
                }
            }

            // 6. Local JSON & CSV Output
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = s.dataOutputTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${s.jsonPathLabel}:\n${state.jsonPath}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${s.csvPathLabel}:\n${state.csvPath}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onExportCsvNow) {
                            Text(s.exportCsvNow)
                        }
                        OutlinedButton(onClick = onShareCsv) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(s.shareCsv)
                        }
                    }
                }
            }

            // 7. Recent Trigger Events
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${s.recentEventsTitle} (${state.totalTriggerCount})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (state.recentEvents.isEmpty()) {
                        Text(
                            text = s.noEventsYet,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                        state.recentEvents.take(10).forEach { event ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "#${event.id} · ${fmt.format(Date(event.timestampMs))}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1f dBFS (%s)", event.effectiveDb, event.selectedBands.joinToString("+") { it.name }),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelMeterBar(levelDb: Float, thresholdDb: Float) {
    val normLevel = ((levelDb + 100f) / 100f).coerceIn(0f, 1f)
    val normThreshold = ((thresholdDb + 100f) / 100f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(normLevel)
                .height(14.dp)
                .background(
                    if (levelDb >= thresholdDb) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(normThreshold)
                .height(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(3.dp)
                    .height(14.dp)
                    .background(Color.Black.copy(alpha = 0.75f))
            )
        }
    }
}

@Composable
private fun BandReadout(label: String, db: Float) {
    AssistChip(
        onClick = {},
        label = {
            Column {
                Text(text = label, style = MaterialTheme.typography.labelSmall)
                Text(
                    text = String.format(Locale.US, "%.1f dB", db),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
private fun RuleSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall)
            Text(text = valueText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
