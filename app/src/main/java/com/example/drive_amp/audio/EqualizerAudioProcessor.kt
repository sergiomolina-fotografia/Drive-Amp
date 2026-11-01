package com.example.drive_amp.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

data class EqPreset(
    val name: String,
    val gainsDb: FloatArray // 8 bandas en dB (-12f .. +12f)
)

/**
 * Ecualizador gráfico real de 8 bandas en cascada (filtros Biquad Peaking EQ) integrado en el
 * pipeline de audio de ExoPlayer. Procesa tanto archivos locales como enlaces de Google Drive y Red.
 */
@OptIn(UnstableApi::class)
object GraphicEqualizerManager {

    const val BAND_COUNT = 8
    private const val PREFS_NAME = "DriveAmpPrefs"
    private const val KEY_EQ_ENABLED = "eq_enabled"
    private const val KEY_EQ_PRESET = "eq_preset"
    private const val KEY_EQ_GAINS = "eq_gains_db"

    val CENTER_FREQS_HZ = floatArrayOf(60f, 170f, 310f, 600f, 1000f, 3000f, 6000f, 14000f)
    val BAND_LABELS = arrayOf("60", "170", "310", "600", "1K", "3K", "6K", "14K")

    val PRESETS = listOf(
        EqPreset("PLANO", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("ROCK", floatArrayOf(5.5f, 4.0f, -2.5f, -1.0f, 2.0f, 4.5f, 6.0f, 6.5f)),
        EqPreset("POP", floatArrayOf(-1.0f, 2.5f, 4.5f, 5.0f, 3.5f, 0.0f, -1.0f, -1.5f)),
        EqPreset("BASS+", floatArrayOf(8.0f, 6.5f, 3.5f, 0.5f, 0.0f, 0.5f, 1.5f, 2.0f)),
        EqPreset("JAZZ", floatArrayOf(4.0f, 3.0f, 1.0f, 2.0f, -1.5f, -1.5f, 2.5f, 4.0f)),
        EqPreset("CLÁSICA", floatArrayOf(4.5f, 3.5f, 2.0f, 0.0f, 0.0f, 1.5f, 3.5f, 4.5f)),
        EqPreset("ELECTRO", floatArrayOf(6.5f, 5.0f, 1.0f, -2.0f, 1.0f, 3.5f, 6.0f, 7.5f))
    )

    @Volatile
    var isEnabled: Boolean = true
        private set

    @Volatile
    var currentPresetName: String = "ROCK"
        private set

    private val currentGainsDb = FloatArray(BAND_COUNT) { i -> PRESETS[1].gainsDb[i] }

    @Volatile
    private var coefficientsDirty: Boolean = true

    fun loadFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isEnabled = prefs.getBoolean(KEY_EQ_ENABLED, true)
        currentPresetName = prefs.getString(KEY_EQ_PRESET, "ROCK") ?: "ROCK"
        val rawJson = prefs.getString(KEY_EQ_GAINS, null)
        if (rawJson != null) {
            try {
                val arr = JSONArray(rawJson)
                if (arr.length() == BAND_COUNT) {
                    for (i in 0 until BAND_COUNT) {
                        currentGainsDb[i] = arr.optDouble(i, 0.0).toFloat().coerceIn(-12f, 12f)
                    }
                }
            } catch (_: Exception) {}
        } else {
            val preset = PRESETS.firstOrNull { it.name == currentPresetName } ?: PRESETS[1]
            for (i in 0 until BAND_COUNT) {
                currentGainsDb[i] = preset.gainsDb[i]
            }
        }
        coefficientsDirty = true
    }

    private fun saveToPrefs(context: Context) {
        val arr = JSONArray()
        for (i in 0 until BAND_COUNT) {
            arr.put(currentGainsDb[i].toDouble())
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_EQ_ENABLED, isEnabled)
            .putString(KEY_EQ_PRESET, currentPresetName)
            .putString(KEY_EQ_GAINS, arr.toString())
            .apply()
    }

    fun getGainsSnapshot(): List<Float> = currentGainsDb.toList()

    fun setEnabled(context: Context, enabled: Boolean) {
        isEnabled = enabled
        saveToPrefs(context)
    }

    fun setBandGain(context: Context, bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until BAND_COUNT) return
        currentGainsDb[bandIndex] = gainDb.coerceIn(-12f, 12f)
        currentPresetName = "MANUAL"
        coefficientsDirty = true
        saveToPrefs(context)
    }

    fun applyPreset(context: Context, preset: EqPreset) {
        for (i in 0 until BAND_COUNT) {
            currentGainsDb[i] = preset.gainsDb[i].coerceIn(-12f, 12f)
        }
        currentPresetName = preset.name
        isEnabled = true
        coefficientsDirty = true
        saveToPrefs(context)
    }

    fun createAudioProcessor(): AudioProcessor = BiquadEqualizerProcessor()

    private class BiquadEqualizerProcessor : BaseAudioProcessor() {
        private var sampleRate = 44100
        private var channels = 2
        private var encoding = C.ENCODING_PCM_16BIT

        // Coeficientes Biquad normalizados por banda: b0, b1, b2, a1, a2
        private val b0 = FloatArray(BAND_COUNT) { 1f }
        private val b1 = FloatArray(BAND_COUNT)
        private val b2 = FloatArray(BAND_COUNT)
        private val a1 = FloatArray(BAND_COUNT)
        private val a2 = FloatArray(BAND_COUNT)

        // Estados de retardo por canal (hasta 2 canales estéreo) y por banda
        private val x1 = Array(2) { FloatArray(BAND_COUNT) }
        private val x2 = Array(2) { FloatArray(BAND_COUNT) }
        private val y1 = Array(2) { FloatArray(BAND_COUNT) }
        private val y2 = Array(2) { FloatArray(BAND_COUNT) }

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
            ) {
                return AudioProcessor.AudioFormat.NOT_SET
            }
            sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
            channels = inputAudioFormat.channelCount.coerceAtLeast(1)
            encoding = inputAudioFormat.encoding
            recomputeCoefficients()
            return inputAudioFormat
        }

        override fun onFlush() {
            for (ch in 0..1) {
                x1[ch].fill(0f)
                x2[ch].fill(0f)
                y1[ch].fill(0f)
                y2[ch].fill(0f)
            }
            recomputeCoefficients()
        }

        private fun recomputeCoefficients() {
            val fs = sampleRate.toFloat()
            val q = 1.41f
            for (i in 0 until BAND_COUNT) {
                val fc = CENTER_FREQS_HZ[i].coerceAtMost(fs * 0.45f)
                val gainDb = currentGainsDb[i]
                if (kotlin.math.abs(gainDb) < 0.05f) {
                    b0[i] = 1f
                    b1[i] = 0f
                    b2[i] = 0f
                    a1[i] = 0f
                    a2[i] = 0f
                    continue
                }
                val a = 10.0.pow(gainDb.toDouble() / 40.0).toFloat()
                val w0 = (2.0 * Math.PI * fc / fs).toFloat()
                val cosW0 = cos(w0)
                val sinW0 = sin(w0)
                val alpha = sinW0 / (2f * q)

                val rawB0 = 1f + alpha * a
                val rawB1 = -2f * cosW0
                val rawB2 = 1f - alpha * a
                val rawA0 = 1f + alpha / a
                val rawA1 = -2f * cosW0
                val rawA2 = 1f - alpha / a

                b0[i] = rawB0 / rawA0
                b1[i] = rawB1 / rawA0
                b2[i] = rawB2 / rawA0
                a1[i] = rawA1 / rawA0
                a2[i] = rawA2 / rawA0
            }
            coefficientsDirty = false
        }

        private fun processSample(sample: Float, ch: Int): Float {
            var s = sample
            val c = ch.coerceIn(0, 1)
            for (b in 0 until BAND_COUNT) {
                val out = b0[b] * s + b1[b] * x1[c][b] + b2[b] * x2[c][b] -
                    a1[b] * y1[c][b] - a2[b] * y2[c][b]
                x2[c][b] = x1[c][b]
                x1[c][b] = s
                y2[c][b] = y1[c][b]
                y1[c][b] = out
                s = out
            }
            // Limitador suave para evitar saturación/clipping cuando se suben varias bandas a +12dB
            return when {
                s > 0.98f -> 0.98f
                s < -0.98f -> -0.98f
                else -> s
            }
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return

            if (coefficientsDirty) {
                recomputeCoefficients()
            }

            val outBuffer = replaceOutputBuffer(remaining)
            if (!isEnabled) {
                outBuffer.put(inputBuffer)
                outBuffer.flip()
                return
            }

            val chCount = channels.coerceAtLeast(1)
            if (encoding == C.ENCODING_PCM_16BIT) {
                val frameBytes = 2 * chCount
                while (inputBuffer.remaining() >= frameBytes) {
                    for (c in 0 until chCount) {
                        val inSample = inputBuffer.short.toFloat() / 32768f
                        val eqSample = processSample(inSample, c)
                        val outShort = (eqSample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        outBuffer.putShort(outShort)
                    }
                }
                while (inputBuffer.hasRemaining()) {
                    outBuffer.put(inputBuffer.get())
                }
            } else if (encoding == C.ENCODING_PCM_FLOAT) {
                val frameBytes = 4 * chCount
                while (inputBuffer.remaining() >= frameBytes) {
                    for (c in 0 until chCount) {
                        val inSample = inputBuffer.float
                        val eqSample = if (inSample.isFinite()) processSample(inSample, c) else 0f
                        outBuffer.putFloat(eqSample)
                    }
                }
                while (inputBuffer.hasRemaining()) {
                    outBuffer.put(inputBuffer.get())
                }
            } else {
                outBuffer.put(inputBuffer)
            }
            outBuffer.flip()
        }
    }
}
