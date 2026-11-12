package com.noisewatch.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.noisewatch.android.model.AudioInputDeviceInfo
import com.noisewatch.android.service.MonitorStateHolder
import com.noisewatch.android.service.NoiseMonitorService
import com.noisewatch.android.storage.EventExporter
import com.noisewatch.android.storage.SettingsRepository
import com.noisewatch.android.ui.NoiseWatchScreen

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var exporter: EventExporter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(this)
        exporter = EventExporter(this)

        val initialSettings = settingsRepo.load()
        MonitorStateHolder.updateSettings(initialSettings)
        MonitorStateHolder.updateState {
            it.copy(
                jsonPath = exporter.jsonFile.absolutePath,
                csvPath = exporter.csvFile.absolutePath
            )
        }

        setContent {
            MaterialTheme {
                val state by MonitorStateHolder.state.collectAsState()
                val settings by MonitorStateHolder.settings.collectAsState()

                var hasMicPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                var devices by remember { mutableStateOf(queryInputDevices()) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    hasMicPermission = grants[Manifest.permission.RECORD_AUDIO] == true
                    devices = queryInputDevices()
                }

                val audioPickerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        val displayName = resolveDisplayName(uri) ?: uri.lastPathSegment ?: "audio"
                        val updated = settings.copy(
                            customAudioUri = uri.toString(),
                            customAudioName = displayName
                        )
                        settingsRepo.save(updated)
                        MonitorStateHolder.updateSettings(updated)
                    }
                }

                LaunchedEffect(Unit) {
                    devices = queryInputDevices()
                }

                NoiseWatchScreen(
                    state = state,
                    settings = settings,
                    devices = devices,
                    hasMicPermission = hasMicPermission,
                    onRequestPermission = {
                        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissionLauncher.launch(perms.toTypedArray())
                    },
                    onUpdateSettings = { updated ->
                        settingsRepo.save(updated)
                        MonitorStateHolder.updateSettings(updated)
                    },
                    onRefreshDevices = {
                        devices = queryInputDevices()
                    },
                    onPickAudio = {
                        audioPickerLauncher.launch(arrayOf("audio/*"))
                    },
                    onClearAudio = {
                        val updated = settings.copy(customAudioUri = null, customAudioName = null)
                        settingsRepo.save(updated)
                        MonitorStateHolder.updateSettings(updated)
                    },
                    onTestAlert = {
                        sendServiceAction(NoiseMonitorService.ACTION_TEST_ALERT, foreground = false)
                    },
                    onStopAlert = {
                        sendServiceAction(NoiseMonitorService.ACTION_STOP_ALERT, foreground = false)
                    },
                    onStartMonitoring = {
                        sendServiceAction(NoiseMonitorService.ACTION_START, foreground = true)
                    },
                    onStopMonitoring = {
                        sendServiceAction(NoiseMonitorService.ACTION_STOP, foreground = false)
                    },
                    onExportCsvNow = {
                        sendServiceAction(NoiseMonitorService.ACTION_EXPORT_CSV, foreground = false)
                    },
                    onShareCsv = {
                        shareCsvFile()
                    }
                )
            }
        }
    }

    private fun sendServiceAction(action: String, foreground: Boolean) {
        val intent = Intent(this, NoiseMonitorService::class.java).apply { this.action = action }
        if (foreground) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun queryInputDevices(): List<AudioInputDeviceInfo> {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { dev ->
            AudioInputDeviceInfo(
                id = dev.id,
                name = dev.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Mic #${dev.id}",
                typeLabel = deviceTypeLabel(dev.type)
            )
        }
    }

    private fun deviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        else -> "Input"
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun shareCsvFile() {
        val file = exporter.csvFile
        if (!file.exists()) {
            exporter.writeCsvExport(MonitorStateHolder.state.value.recentEvents)
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share CSV"))
    }
}
