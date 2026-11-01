package com.example.drive_amp.audio

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analizador de espectro (FFT en tiempo real) y forma de onda conectado directamente al flujo PCM
 * de ExoPlayer mediante [TeeAudioProcessor]. No requiere permiso de micrófono (RECORD_AUDIO) y
 * funciona igual con archivos locales, enlaces de Google Drive y streams HTTP.
 */
@OptIn(UnstableApi::class)
object AudioSpectrumAnalyzer {

    const val NUM_BANDS = 24
    const val WAVE_SAMPLES = 64
    private const val FFT_SIZE = 512

    @Volatile
    private var sampleRateHz: Int = 44100

    @Volatile
    private var channelCount: Int = 2

    @Volatile
    private var encoding: Int = C.ENCODING_PCM_16BIT

    @Volatile
    var lastFrameTimestampMs: Long = 0L
        private set

    // Anillo circular de muestras mono (-1f..1f)
    private val ringBuffer = FloatArray(FFT_SIZE)
    private var ringWritePos = 0

    // Buffers reutilizables para FFT Radix-2 (sin allocs por frame)
    private val fftReal = FloatArray(FFT_SIZE)
    private val fftImag = FloatArray(FFT_SIZE)
    private val hannWindow = FloatArray(FFT_SIZE) { i ->
        (0.5f * (1.0 - cos(2.0 * Math.PI * i / (FFT_SIZE - 1)))).toFloat()
    }

    // Salidas leídas desde la UI (Compose Canvas)
    val liveBands = FloatArray(NUM_BANDS)
    val liveWave = FloatArray(WAVE_SAMPLES)

    @Volatile
    var liveBassPulse: Float = 0f
        private set

    // Control automático de ganancia suave para que canciones bajas y fuertes aprovechen toda la pantalla LED
    private var runningPeakRms = 0.18f

    val audioBufferSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            this@AudioSpectrumAnalyzer.sampleRateHz = sampleRateHz.coerceAtLeast(8000)
            this@AudioSpectrumAnalyzer.channelCount = channelCount.coerceAtLeast(1)
            this@AudioSpectrumAnalyzer.encoding = encoding
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            val dup = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
            val remainingBytes = dup.remaining()
            if (remainingBytes <= 0) return

            val ch = channelCount.coerceAtLeast(1)
            var samplesAdded = 0

            when (encoding) {
                C.ENCODING_PCM_16BIT -> {
                    val frameBytes = 2 * ch
                    while (dup.remaining() >= frameBytes) {
                        var sum = 0f
                        for (c in 0 until ch) {
                            sum += dup.short.toFloat() / 32768f
                        }
                        ringBuffer[ringWritePos] = (sum / ch).coerceIn(-1f, 1f)
                        ringWritePos = (ringWritePos + 1) and (FFT_SIZE - 1)
                        samplesAdded++
                    }
                }
                C.ENCODING_PCM_FLOAT -> {
                    val frameBytes = 4 * ch
                    while (dup.remaining() >= frameBytes) {
                        var sum = 0f
                        for (c in 0 until ch) {
                            val s = dup.float
                            if (s.isFinite()) sum += s
                        }
                        ringBuffer[ringWritePos] = (sum / ch).coerceIn(-1f, 1f)
                        ringWritePos = (ringWritePos + 1) and (FFT_SIZE - 1)
                        samplesAdded++
                    }
                }
                C.ENCODING_PCM_8BIT -> {
                    while (dup.remaining() >= ch) {
                        var sum = 0f
                        for (c in 0 until ch) {
                            val u = dup.get().toInt() and 0xFF
                            sum += (u - 128) / 128f
                        }
                        ringBuffer[ringWritePos] = (sum / ch).coerceIn(-1f, 1f)
                        ringWritePos = (ringWritePos + 1) and (FFT_SIZE - 1)
                        samplesAdded++
                    }
                }
                else -> return
            }

            if (samplesAdded > 0) {
                computeSpectrumAndWave()
                lastFrameTimestampMs = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun computeSpectrumAndWave() {
        var rmsSum = 0f
        val startPos = ringWritePos
        for (i in 0 until FFT_SIZE) {
            val s = ringBuffer[(startPos + i) and (FFT_SIZE - 1)]
            rmsSum += s * s
            fftReal[i] = s * hannWindow[i]
            fftImag[i] = 0f
        }
        val rms = sqrt(rmsSum / FFT_SIZE)
        runningPeakRms = max(rms, runningPeakRms * 0.992f).coerceIn(0.06f, 0.85f)
        val normGain = (0.32f / runningPeakRms).coerceIn(0.6f, 3.2f)

        // Actualizar los 64 puntos de la forma de onda real para el modo ONDA
        val stride = FFT_SIZE / WAVE_SAMPLES
        for (w in 0 until WAVE_SAMPLES) {
            val idx = (startPos + w * stride) and (FFT_SIZE - 1)
            liveWave[w] = (ringBuffer[idx] * normGain * 1.35f).coerceIn(-0.98f, 0.98f)
        }

        // FFT Radix-2 en el lugar (N = 512)
        fftInPlace(fftReal, fftImag)

        // Agrupar los bins de frecuencia en 24 bandas logarítmicas (desde ~45 Hz hasta ~13 kHz)
        val half = FFT_SIZE / 2
        val minBin = 1f
        val maxBin = (half * 0.65f).coerceAtLeast(32f)
        var bassAccum = 0f

        for (b in 0 until NUM_BANDS) {
            val t0 = b.toFloat() / NUM_BANDS
            val t1 = (b + 1).toFloat() / NUM_BANDS
            val binLo = (minBin * (maxBin / minBin).pow(t0)).toInt().coerceIn(1, half - 2)
            val binHi = max(binLo + 1, (minBin * (maxBin / minBin).pow(t1)).toInt().coerceIn(2, half - 1))

            var magSum = 0f
            for (k in binLo..binHi) {
                val re = fftReal[k]
                val im = fftImag[k]
                magSum += sqrt(re * re + im * im)
            }
            val avgMag = magSum / (binHi - binLo + 1).toFloat()

            // Pre-énfasis de frecuencias medias y altas (compensa la caída natural 1/f de la música)
            val freqTilt = 1.0f + b * 0.16f
            val scaled = (avgMag / (FFT_SIZE * 0.042f)) * freqTilt * normGain
            // Compresión logarítmica suave tipo dB para que los golpes se noten nítidos
            val dbLike = (ln(1f + scaled * 4.5f) / ln(5.5f)).coerceIn(0f, 1f)

            liveBands[b] = dbLike
            if (b < 4) {
                bassAccum = max(bassAccum, dbLike)
            }
        }
        liveBassPulse = bassAccum.coerceIn(0f, 1f)
    }

    private fun fftInPlace(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = (-2.0 * Math.PI / len).toFloat()
            val wLenR = cos(angle)
            val wLenI = sin(angle)
            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                for (k in 0 until halfLen) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vIdx = i + k + halfLen
                    val vR = real[vIdx] * wR - imag[vIdx] * wI
                    val vI = real[vIdx] * wI + imag[vIdx] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[vIdx] = uR - vR
                    imag[vIdx] = uI - vI
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
