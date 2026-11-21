package com.noisewatch.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Plays either a user-selected local audio file (via SAF Uri) or a built-in synthesized chime.
 * Also provides playKeepAlive() supporting a custom keep-alive audio file or built-in soft pulse.
 * Uses USAGE_MEDIA + CONTENT_TYPE_MUSIC so audio routes strictly to the active output device
 * (e.g., Bluetooth speaker) instead of dual-playing through the phone speaker and Bluetooth.
 */
class AlertPlayer(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaPlayer: MediaPlayer? = null
    private var builtInJob: Job? = null
    private var audioTrack: AudioTrack? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Volatile
    var isKeepAlivePlaying: Boolean = false
        private set

    @Synchronized
    fun play(customAudioUri: String?) {
        stop()
        isKeepAlivePlaying = false
        if (!customAudioUri.isNullOrBlank()) {
            val started = playCustomUri(Uri.parse(customAudioUri))
            if (started) return
        }
        playBuiltInChime()
    }

    @Synchronized
    fun playKeepAlive(keepAliveAudioUri: String?, force: Boolean = false) {
        if (!force && _isPlaying.value) return
        stop()
        isKeepAlivePlaying = true
        if (!keepAliveAudioUri.isNullOrBlank()) {
            val started = playCustomUri(Uri.parse(keepAliveAudioUri))
            if (started) return
        }
        playKeepAlivePulse()
    }

    /**
     * Plays a 1.0s gentle pulse (440 Hz @ 30% amplitude) — long enough to survive
     * Bluetooth A2DP wake-up latency without startling the user.
     */
    private fun playKeepAlivePulse() {
        _isPlaying.value = true
        builtInJob = scope.launch {
            val sampleRate = 44100
            val durationSec = 1.0
            val numSamples = (sampleRate * durationSec).toInt()
            val pcm = ShortArray(numSamples)

            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val env = sin(PI * t / durationSec)
                val sample = sin(2.0 * PI * 440.0 * t) * env * 0.30
                pcm[i] = (sample * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }

            playPcmStatic(sampleRate, pcm, 1100L)
        }
    }

    private fun playCustomUri(uri: Uri): Boolean {
        return try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(context, uri)
                setOnCompletionListener {
                    stop()
                }
                setOnErrorListener { _, _, _ ->
                    stop()
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = mp
            _isPlaying.value = true
            true
        } catch (_: Exception) {
            isKeepAlivePlaying = false
            false
        }
    }

    private fun playBuiltInChime() {
        _isPlaying.value = true
        builtInJob = scope.launch {
            val sampleRate = 44100
            val durationSec = 1.2
            val numSamples = (sampleRate * durationSec).toInt()
            val pcm = ShortArray(numSamples)

            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val freq = if (t < 0.35) 880.0 else 1174.66
                val localT = if (t < 0.35) t else (t - 0.35)
                val attack = (localT / 0.015).coerceAtMost(1.0)
                val decay = exp(-localT * 4.5)
                val sample = sin(2.0 * PI * freq * t) * attack * decay * 0.65
                pcm[i] = (sample * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }

            playPcmStatic(sampleRate, pcm, 1300L)
        }
    }

    private fun playPcmStatic(sampleRate: Int, pcm: ShortArray, waitMs: Long) {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack = track
            track.write(pcm, 0, pcm.size)
            track.play()

            val startMs = System.currentTimeMillis()
            while (_isPlaying.value && System.currentTimeMillis() - startMs < waitMs) {
                Thread.sleep(50L)
            }
        } catch (_: Exception) {
        } finally {
            releaseBuiltInTrack()
            _isPlaying.value = false
            isKeepAlivePlaying = false
        }
    }

    @Synchronized
    fun stop() {
        _isPlaying.value = false
        isKeepAlivePlaying = false
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
        }

        builtInJob?.cancel()
        builtInJob = null
        releaseBuiltInTrack()
    }

    private fun releaseBuiltInTrack() {
        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
                release()
            }
        } catch (_: Exception) {
        } finally {
            audioTrack = null
        }
    }
}
