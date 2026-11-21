package com.noisewatch.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Plays either a user-selected local audio file (copied to app storage or SAF Uri)
 * or a built-in synthesized tone via AudioTrack MODE_STREAM (reliable on Bluetooth A2DP).
 * Includes a completion watchdog so isPlaying can never get stuck true.
 */
class AlertPlayer(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var builtInJob: Job? = null
    private var watchdogJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var playGeneration: Long = 0L

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Volatile
    var isKeepAlivePlaying: Boolean = false
        private set

    @Synchronized
    fun play(customAudioSource: String?, onResult: ((String) -> Unit)? = null) {
        stop()
        val gen = ++playGeneration
        isKeepAlivePlaying = false
        if (!customAudioSource.isNullOrBlank()) {
            _isPlaying.value = true
            mainHandler.post {
                startCustomSourceOnMain(
                    gen = gen,
                    source = customAudioSource,
                    fallbackToKeepAlivePulse = false,
                    onResult = onResult
                )
            }
            return
        }
        playBuiltInChime(gen, onResult)
    }

    @Synchronized
    fun playKeepAlive(
        keepAliveAudioSource: String?,
        force: Boolean = false,
        onResult: ((String) -> Unit)? = null
    ) {
        if (!force && _isPlaying.value) {
            onResult?.invoke("Skipped (audio busy)")
            return
        }
        stop()
        val gen = ++playGeneration
        isKeepAlivePlaying = true
        if (!keepAliveAudioSource.isNullOrBlank()) {
            _isPlaying.value = true
            mainHandler.post {
                startCustomSourceOnMain(
                    gen = gen,
                    source = keepAliveAudioSource,
                    fallbackToKeepAlivePulse = true,
                    onResult = onResult
                )
            }
            return
        }
        playKeepAlivePulse(gen, onResult)
    }

    @Synchronized
    private fun startCustomSourceOnMain(
        gen: Long,
        source: String,
        fallbackToKeepAlivePulse: Boolean,
        onResult: ((String) -> Unit)?
    ) {
        if (gen != playGeneration) return
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                val localFile = File(source)
                if (localFile.exists() && localFile.isFile) {
                    setDataSource(localFile.absolutePath)
                } else {
                    setDataSource(context, Uri.parse(source))
                }
                setOnCompletionListener {
                    stopIfGeneration(gen)
                }
                setOnErrorListener { _, _, _ ->
                    stopIfGeneration(gen)
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = mp
            _isPlaying.value = true
            onResult?.invoke("Custom file")
            startMediaPlayerWatchdog(gen, mp)
        } catch (e: Exception) {
            mediaPlayer = null
            val reason = e.javaClass.simpleName
            if (gen == playGeneration) {
                if (fallbackToKeepAlivePulse) {
                    onResult?.invoke("Fallback pulse ($reason)")
                    playKeepAlivePulse(gen, null)
                } else {
                    onResult?.invoke("Fallback chime ($reason)")
                    playBuiltInChime(gen, null)
                }
            }
        }
    }

    private fun startMediaPlayerWatchdog(gen: Long, mp: MediaPlayer) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            val maxWaitMs = runCatching {
                val dur = mp.duration
                if (dur > 0) (dur + 2000L).coerceAtMost(120_000L) else 30_000L
            }.getOrDefault(30_000L)

            val startMs = System.currentTimeMillis()
            delay(300L)
            while (isActive && gen == playGeneration) {
                val stillPlaying = runCatching { mp.isPlaying }.getOrDefault(false)
                if (!stillPlaying || (System.currentTimeMillis() - startMs) >= maxWaitMs) {
                    stopIfGeneration(gen)
                    break
                }
                delay(250L)
            }
        }
    }

    /**
     * Plays a 1.0s gentle pulse (440 Hz @ 35% amplitude) using MODE_STREAM
     * for reliable Bluetooth A2DP playback across all Android devices.
     */
    private fun playKeepAlivePulse(gen: Long, onResult: ((String) -> Unit)?) {
        _isPlaying.value = true
        onResult?.invoke("Built-in pulse")
        builtInJob = scope.launch {
            val sampleRate = 44100
            val durationSec = 1.0
            val numSamples = (sampleRate * durationSec).toInt()
            val pcm = ShortArray(numSamples)

            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val env = sin(PI * t / durationSec)
                val sample = sin(2.0 * PI * 440.0 * t) * env * 0.35
                pcm[i] = (sample * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }

            playPcmStream(gen, sampleRate, pcm)
        }
    }

    private fun playBuiltInChime(gen: Long, onResult: ((String) -> Unit)?) {
        _isPlaying.value = true
        onResult?.invoke("Built-in chime")
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

            playPcmStream(gen, sampleRate, pcm)
        }
    }

    private fun playPcmStream(gen: Long, sampleRate: Int, pcm: ShortArray) {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

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
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            synchronized(this) {
                if (gen != playGeneration) {
                    track.release()
                    return
                }
                audioTrack = track
            }

            track.play()
            var offset = 0
            val chunkSize = 2048
            while (offset < pcm.size && _isPlaying.value && gen == playGeneration) {
                val count = minOf(chunkSize, pcm.size - offset)
                val written = track.write(pcm, offset, count)
                if (written <= 0) break
                offset += written
            }
            // Wait briefly for hardware buffer drain
            if (_isPlaying.value && gen == playGeneration) {
                Thread.sleep(150L)
            }
        } catch (_: Exception) {
        } finally {
            synchronized(this) {
                releaseBuiltInTrack()
                if (gen == playGeneration) {
                    _isPlaying.value = false
                    isKeepAlivePlaying = false
                }
            }
        }
    }

    @Synchronized
    private fun stopIfGeneration(gen: Long) {
        if (gen == playGeneration) {
            stop()
        }
    }

    @Synchronized
    fun stop() {
        playGeneration++
        _isPlaying.value = false
        isKeepAlivePlaying = false
        watchdogJob?.cancel()
        watchdogJob = null
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
