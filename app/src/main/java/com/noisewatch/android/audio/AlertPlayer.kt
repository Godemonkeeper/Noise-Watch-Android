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
 * Supports immediate one-tap stop control.
 */
class AlertPlayer(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaPlayer: MediaPlayer? = null
    private var builtInJob: Job? = null
    private var audioTrack: AudioTrack? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Synchronized
    fun play(customAudioUri: String?) {
        stop()
        if (!customAudioUri.isNullOrBlank()) {
            val started = playCustomUri(Uri.parse(customAudioUri))
            if (started) return
        }
        playBuiltInChime()
    }

    private fun playCustomUri(uri: Uri): Boolean {
        return try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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

            // Two-tone chime: 880 Hz followed by 1174.66 Hz (D6) with smooth exponential decay
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val freq = if (t < 0.35) 880.0 else 1174.66
                val localT = if (t < 0.35) t else (t - 0.35)
                val attack = (localT / 0.015).coerceAtMost(1.0)
                val decay = exp(-localT * 4.5)
                val sample = sin(2.0 * PI * freq * t) * attack * decay * 0.65
                pcm[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
                while (_isPlaying.value && System.currentTimeMillis() - startMs < 1300L) {
                    Thread.sleep(50L)
                }
            } catch (_: Exception) {
                // Ignore playback interruption
            } finally {
                releaseBuiltInTrack()
                _isPlaying.value = false
            }
        }
    }

    @Synchronized
    fun stop() {
        _isPlaying.value = false
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
