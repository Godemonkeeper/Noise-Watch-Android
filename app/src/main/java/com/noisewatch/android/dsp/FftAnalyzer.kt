package com.noisewatch.android.dsp

import com.noisewatch.android.model.BandLevels
import com.noisewatch.android.model.FrequencyBand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2048-point Radix-2 Cooley-Tukey FFT analyzer with Hann windowing.
 * Computes dBFS levels for LOW (20-250 Hz), MID (250-2000 Hz), and HIGH (2000-8000 Hz).
 */
class FftAnalyzer(
    private val sampleRate: Int = 44100,
    val fftSize: Int = 2048
) {
    private val window = DoubleArray(fftSize) { i ->
        0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))
    }

    // Sum of squared window coefficients for Parseval energy normalization
    private val windowEnergyNorm = window.sumOf { it * it }
    private val real = DoubleArray(fftSize)
    private val imag = DoubleArray(fftSize)

    fun analyze(pcm16: ShortArray, length: Int = pcm16.size): BandLevels {
        val n = minOf(length, fftSize)
        var sumSqTime = 0.0

        for (i in 0 until fftSize) {
            val sample = if (i < n) pcm16[i] / 32768.0 else 0.0
            sumSqTime += sample * sample
            real[i] = sample * window[i]
            imag[i] = 0.0
        }

        val timeRms = sqrt(sumSqTime / fftSize.coerceAtLeast(1))
        val overallDb = amplitudeToDbFs(timeRms)

        fft(real, imag)

        val binHz = sampleRate.toDouble() / fftSize
        val half = fftSize / 2

        var lowPower = 0.0
        var midPower = 0.0
        var highPower = 0.0

        for (bin in 1 until half) {
            val freq = bin * binHz
            val magSq = real[bin] * real[bin] + imag[bin] * imag[bin]
            // Single-sided spectrum power normalized by window energy & FFT size
            val power = (2.0 * magSq) / (fftSize * windowEnergyNorm)

            when {
                freq >= FrequencyBand.LOW.minHz && freq < FrequencyBand.LOW.maxHz -> lowPower += power
                freq >= FrequencyBand.MID.minHz && freq < FrequencyBand.MID.maxHz -> midPower += power
                freq >= FrequencyBand.HIGH.minHz && freq <= minOf(FrequencyBand.HIGH.maxHz, sampleRate / 2.0) -> highPower += power
            }
        }

        return BandLevels(
            lowDb = amplitudeToDbFs(sqrt(lowPower)),
            midDb = amplitudeToDbFs(sqrt(midPower)),
            highDb = amplitudeToDbFs(sqrt(highPower)),
            overallDb = overallDb
        )
    }

    private fun amplitudeToDbFs(rms: Double): Float {
        if (rms <= 1e-5) return -100f
        val db = 20.0 * log10(rms)
        return db.toFloat().coerceIn(-100f, 0f)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tmpR = re[i]; re[i] = re[j]; re[j] = tmpR
                val tmpI = im[i]; im[i] = im[j]; im[j] = tmpI
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenR = cos(ang)
            val wLenI = sin(ang)
            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (k in 0 until len / 2) {
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val tR = re[i + k + len / 2] * wR - im[i + k + len / 2] * wI
                    val tI = re[i + k + len / 2] * wI + im[i + k + len / 2] * wR
                    re[i + k] = uR + tR
                    im[i + k] = uI + tI
                    re[i + k + len / 2] = uR - tR
                    im[i + k + len / 2] = uI - tI
                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
