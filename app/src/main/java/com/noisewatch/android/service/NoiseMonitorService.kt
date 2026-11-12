package com.noisewatch.android.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.noisewatch.android.MainActivity
import com.noisewatch.android.audio.AlertPlayer
import com.noisewatch.android.dsp.FftAnalyzer
import com.noisewatch.android.engine.RuleEvaluator
import com.noisewatch.android.model.AppLanguage
import com.noisewatch.android.model.TriggerEvent
import com.noisewatch.android.storage.EventExporter
import com.noisewatch.android.storage.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NoiseMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var csvJob: Job? = null
    private var alertObserverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var exporter: EventExporter
    private lateinit var alertPlayer: AlertPlayer
    private val ruleEvaluator = RuleEvaluator()
    private val allEvents = mutableListOf<TriggerEvent>()
    private var nextEventId = 1L

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        exporter = EventExporter(this)
        alertPlayer = AlertPlayer(this)

        val loaded = settingsRepo.load()
        MonitorStateHolder.updateSettings(loaded)
        MonitorStateHolder.updateState {
            it.copy(
                jsonPath = exporter.jsonFile.absolutePath,
                csvPath = exporter.csvFile.absolutePath
            )
        }

        createNotificationChannel()
        alertObserverJob = serviceScope.launch {
            alertPlayer.isPlaying.collectLatest { playing ->
                MonitorStateHolder.updateState { it.copy(isAlertPlaying = playing) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
            ACTION_STOP_ALERT -> alertPlayer.stop()
            ACTION_TEST_ALERT -> alertPlayer.play(MonitorStateHolder.settings.value.customAudioUri)
            ACTION_EXPORT_CSV -> exportCsvNow()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            MonitorStateHolder.updateState {
                it.copy(isMonitoring = false, errorMessage = "Missing RECORD_AUDIO permission")
            }
            stopSelf()
            return
        }

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType)
        acquireWakeLock()
        ruleEvaluator.reset()

        MonitorStateHolder.updateState {
            it.copy(isMonitoring = true, errorMessage = null)
        }

        monitorJob = serviceScope.launch {
            runAudioLoop()
        }

        csvJob = serviceScope.launch {
            while (isActive) {
                delay(CSV_EXPORT_INTERVAL_MS)
                exportCsvNow()
            }
        }
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoiseWatch::MicMonitorLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        }
        wakeLock = null
    }

    private suspend fun runAudioLoop() {
        val sampleRate = 44100
        val fftAnalyzer = FftAnalyzer(sampleRate = sampleRate, fftSize = 2048)
        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(fftAnalyzer.fftSize * 2)

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufBytes * 2
            )
        } catch (e: SecurityException) {
            MonitorStateHolder.updateState {
                it.copy(isMonitoring = false, errorMessage = e.message)
            }
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            MonitorStateHolder.updateState {
                it.copy(isMonitoring = false, errorMessage = "AudioRecord initialization failed")
            }
            return
        }

        applyPreferredInputDevice(audioRecord, MonitorStateHolder.settings.value.selectedDeviceId)

        val buffer = ShortArray(fftAnalyzer.fftSize)
        var lastDeviceId = MonitorStateHolder.settings.value.selectedDeviceId
        var lastJsonWriteMs = 0L

        try {
            audioRecord.startRecording()
            while (serviceScope.isActive && MonitorStateHolder.state.value.isMonitoring) {
                val currentSettings = MonitorStateHolder.settings.value
                if (currentSettings.selectedDeviceId != lastDeviceId) {
                    lastDeviceId = currentSettings.selectedDeviceId
                    applyPreferredInputDevice(audioRecord, lastDeviceId)
                }

                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val levels = fftAnalyzer.analyze(buffer, read)
                    val effectiveDb = levels.effectiveDb(currentSettings.selectedBands)
                    val nowMs = System.currentTimeMillis()
                    val eval = ruleEvaluator.evaluate(nowMs, effectiveDb, currentSettings)

                    if (eval.triggered) {
                        val alertMode = if (!currentSettings.customAudioUri.isNullOrBlank()) {
                            currentSettings.customAudioName ?: "Custom Audio"
                        } else {
                            "Built-in Default"
                        }
                        val event = TriggerEvent(
                            id = nextEventId++,
                            timestampMs = nowMs,
                            effectiveDb = effectiveDb,
                            lowDb = levels.lowDb,
                            midDb = levels.midDb,
                            highDb = levels.highDb,
                            thresholdDb = currentSettings.thresholdDb,
                            selectedBands = currentSettings.selectedBands,
                            impactCount = currentSettings.impactCount,
                            timeWindowSec = currentSettings.timeWindowSec,
                            cooldownSec = currentSettings.cooldownSec,
                            alertMode = alertMode
                        )
                        synchronized(allEvents) {
                            allEvents.add(0, event)
                            if (allEvents.size > MAX_STORED_EVENTS) {
                                allEvents.removeAt(allEvents.lastIndex)
                            }
                        }
                        alertPlayer.play(currentSettings.customAudioUri)
                    }

                    val snapshotEvents = synchronized(allEvents) { allEvents.take(50) }
                    val updatedState = MonitorStateHolder.state.value.copy(
                        isMonitoring = true,
                        levels = levels,
                        effectiveDb = effectiveDb,
                        activeHitsInWindow = eval.activeHitsInWindow,
                        cooldownRemainingSec = eval.cooldownRemainingSec,
                        recentEvents = snapshotEvents,
                        totalTriggerCount = if (eval.triggered) {
                            MonitorStateHolder.state.value.totalTriggerCount + 1
                        } else {
                            MonitorStateHolder.state.value.totalTriggerCount
                        }
                    )
                    MonitorStateHolder.updateState { updatedState }

                    // Real-time JSON snapshot overwrite (on trigger or every ~500ms)
                    if (eval.triggered || nowMs - lastJsonWriteMs >= 500L) {
                        lastJsonWriteMs = nowMs
                        exporter.writeJsonSnapshot(updatedState, currentSettings)
                    }
                }
            }
        } finally {
            runCatching {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
                audioRecord.release()
            }
        }
    }

    private fun applyPreferredInputDevice(audioRecord: AudioRecord, deviceId: Int?) {
        if (deviceId == null) {
            audioRecord.preferredDevice = null
            return
        }
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val target: AudioDeviceInfo? = devices.firstOrNull { it.id == deviceId }
        audioRecord.preferredDevice = target
    }

    private fun exportCsvNow() {
        val snapshot = synchronized(allEvents) { allEvents.toList() }
        val exportedAt = exporter.writeCsvExport(snapshot)
        MonitorStateHolder.updateState { it.copy(lastCsvExportMs = exportedAt) }
    }

    private fun stopMonitoring() {
        MonitorStateHolder.updateState {
            it.copy(isMonitoring = false, activeHitsInWindow = 0, cooldownRemainingSec = 0)
        }
        monitorJob?.cancel()
        monitorJob = null
        csvJob?.cancel()
        csvJob = null
        releaseWakeLock()
        exporter.writeJsonSnapshot(MonitorStateHolder.state.value, MonitorStateHolder.settings.value)
        exportCsvNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Noise Watch Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val isZh = MonitorStateHolder.settings.value.language == AppLanguage.ZH
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NoiseMonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isZh) "Noise Watch 正在监听" else "Noise Watch Monitoring")
            .setContentText(if (isZh) "本地频段分析与阈值提醒运行中" else "Local band analysis & threshold alert active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (isZh) "停止监听" else "Stop",
                stopIntent
            )
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        alertPlayer.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "noise_watch_monitor"
        const val NOTIFICATION_ID = 1001
        const val CSV_EXPORT_INTERVAL_MS = 5 * 60 * 1000L
        const val MAX_STORED_EVENTS = 500

        const val ACTION_START = "com.noisewatch.android.START"
        const val ACTION_STOP = "com.noisewatch.android.STOP"
        const val ACTION_STOP_ALERT = "com.noisewatch.android.STOP_ALERT"
        const val ACTION_TEST_ALERT = "com.noisewatch.android.TEST_ALERT"
        const val ACTION_EXPORT_CSV = "com.noisewatch.android.EXPORT_CSV"
    }
}
