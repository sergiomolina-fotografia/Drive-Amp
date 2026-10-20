package com.example.drive_amp.art

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.math.abs

/**
 * Dibuja la carátula estilo Drive-Amp (barra de título azul, LCD negro, barras verdes
 * con topes naranjas y el título en verde monoespaciado). Se usa como carátula de cada
 * canción en la pantalla del auto.
 */
object WinampArt {
    private const val SIZE = 512
    private val GREEN = Color.rgb(0, 224, 0)
    private val DIM_GREEN = Color.rgb(0, 160, 0)
    private val ORANGE = Color.rgb(255, 157, 0)

    fun render(title: String, index: Int, total: Int): ByteArray {
        val s = SIZE
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val sf = s.toFloat()

        c.drawColor(Color.rgb(20, 24, 34))

        // Marco
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = Color.rgb(109, 118, 144)
        c.drawRect(1.5f, 1.5f, sf - 1.5f, sf - 1.5f, p)
        p.style = Paint.Style.FILL

        // Barra de título (degradado azul)
        p.shader = LinearGradient(0f, 3f, 0f, 58f, Color.rgb(60, 90, 150), Color.rgb(29, 44, 82), Shader.TileMode.CLAMP)
        c.drawRect(3f, 3f, sf - 3f, 58f, p)
        p.shader = null
        p.color = Color.rgb(230, 236, 255)
        p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        p.textSize = 27f
        p.textAlign = Paint.Align.CENTER
        c.drawText("DRIVE-AMP", sf / 2f, 40f, p)

        // Pantalla LCD
        p.color = Color.BLACK
        c.drawRect(22f, 75f, sf - 22f, sf - 22f, p)

        // Barras decorativas (siempre iguales para la misma canción)
        val rnd = Random(title.hashCode().toLong())
        val n = 19
        val gap = 5f
        val bw = (sf - 60f - gap * (n - 1)) / n
        val base = 235f
        val maxH = 125f
        for (i in 0 until n) {
            val v = 0.25f + 0.75f * (rnd.nextFloat() * 0.7f + 0.3f * (1f - abs(i - n / 2f) / (n / 2f)))
            val bh = v * maxH
            val x = 30f + i * (bw + gap)
            p.shader = LinearGradient(
                0f, base, 0f, base - maxH,
                intArrayOf(GREEN, GREEN, ORANGE), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(x, base - bh, x + bw, base, p)
            p.shader = null
            p.color = ORANGE
            c.drawRect(x, base - bh - 5f, x + bw, base - bh - 2.5f, p)
        }

        // Título en recuadro verde oscuro (hasta 3 líneas)
        p.typeface = Typeface.MONOSPACE
        p.textAlign = Paint.Align.LEFT
        p.textSize = 29f
        val maxW = sf - 44f - 24f
        val lines = wrap(p, title.ifBlank { "DRIVE-AMP" }, maxW, 3)
        val boxTop = 258f
        val lineH = 38f
        p.color = Color.rgb(2, 16, 2)
        c.drawRect(30f, boxTop, sf - 30f, boxTop + lines.size * lineH + 14f, p)
        p.color = GREEN
        var y = boxTop + 32f
        for (ln in lines) {
            c.drawText(ln, 42f, y, p)
            y += lineH
        }

        // Pie: número de pista y origen
        p.textSize = 20f
        p.color = GREEN
        c.drawText(String.format("%02d / %d", index, total), 30f, sf - 40f, p)
        p.color = DIM_GREEN
        p.textAlign = Paint.Align.RIGHT
        c.drawText("STREAMING DRIVE", sf - 30f, sf - 40f, p)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun wrap(p: Paint, text: String, maxWidth: Float, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()
        var rest = text.trim()
        while (rest.isNotEmpty() && lines.size < maxLines) {
            val count = p.breakText(rest, true, maxWidth, null)
            if (count <= 0) break
            var cut = count
            if (cut < rest.length) {
                val sp = rest.lastIndexOf(' ', cut)
                if (sp > 0) cut = sp
            }
            var line = rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
            if (lines.size == maxLines - 1 && rest.isNotEmpty()) {
                line = line.dropLast(1) + "…"
                rest = ""
            }
            lines.add(line)
        }
        return lines
    }
}
