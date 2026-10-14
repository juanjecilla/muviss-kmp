package com.codingpit.muviss.storeshot

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Point
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Typeface
import java.io.File

/**
 * Generated poster art for [StoreShotCatalog]'s invented titles: a two-tone
 * gradient, one geometric motif and the title set in the app's own Schibsted
 * Grotesk. Deterministic per title, so a regenerated screenshot only changes
 * when the UI does.
 */
internal object StoreShotPosters {

    // 2x TMDB's w342: the triage card draws a poster almost screen-wide.
    private const val WIDTH = 684
    private const val HEIGHT = 1026
    private const val S = WIDTH / 342f

    private val palettes = listOf(
        0xFF1F4D3E to 0xFF9CD3C0,
        0xFF614000 to 0xFFFFCB6B,
        0xFF3A2E12 to 0xFFD8C4A2,
        0xFF22304A to 0xFF8FB3E8,
        0xFF4A1F2E to 0xFFE89BB0,
        0xFF2B2822 to 0xFFE9E2D4,
        0xFF173A4A to 0xFF7FD1E8,
        0xFF3D2A55 to 0xFFC3A6F0,
    )

    private val typeface: Typeface by lazy {
        val font = File(repoRoot(), "core/designsystem/src/commonMain/composeResources/font/SchibstedGrotesk-Bold.ttf")
        FontMgr.default.makeFromFile(font.absolutePath) ?: error("cannot load $font")
    }

    /** [index] is the title's place in the catalogue: neighbours never share a palette. */
    fun render(title: String, index: Int): Bitmap {
        val seed = index
        val (dark, light) = palettes[seed % palettes.size]
        val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(WIDTH, HEIGHT)) }
        val canvas = Canvas(bitmap)

        val background = Paint().apply {
            shader = Shader.makeLinearGradient(Point(0f, 0f), Point(WIDTH.toFloat(), HEIGHT.toFloat()), intArrayOf(dark.toInt(), mix(dark, light).toInt()))
        }
        canvas.drawRect(Rect.makeWH(WIDTH.toFloat(), HEIGHT.toFloat()), background)

        val motif = Paint().apply { color = Color.withA(light.toInt(), 150) }
        when (seed % 3) {
            0 -> canvas.drawCircle(WIDTH * 0.66f, HEIGHT * 0.32f, WIDTH * 0.30f, motif)
            1 -> for (i in 0 until 5) canvas.drawRect(Rect.makeXYWH(0f, HEIGHT * 0.12f + i * 34f * S, WIDTH.toFloat(), 14f * S), motif)
            else -> canvas.drawRect(Rect.makeXYWH(WIDTH * 0.18f, HEIGHT * 0.10f, WIDTH * 0.64f, WIDTH * 0.64f), motif)
        }

        val text = Paint().apply { color = Color.withA(Color.WHITE, 240) }
        val font = Font(typeface, 40f * S)
        val lines = wrap(title, font, WIDTH - 48f * S)
        var y = HEIGHT - 40f * S - (lines.size - 1) * 46f * S
        for (line in lines) {
            canvas.drawString(line, 24f * S, y, font, text)
            y += 46f * S
        }
        return bitmap
    }

    private fun wrap(title: String, font: Font, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in title.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (font.measureTextWidth(candidate) <= maxWidth || current.isEmpty()) {
                current = candidate
            } else {
                lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun mix(a: Long, b: Long): Long {
        fun ch(c: Long, shift: Int) = (c shr shift) and 0xFF
        val r = (ch(a, 16) * 3 + ch(b, 16)) / 4
        val g = (ch(a, 8) * 3 + ch(b, 8)) / 4
        val bl = (ch(a, 0) * 3 + ch(b, 0)) / 4
        return (0xFFL shl 24) or (r shl 16) or (g shl 8) or bl
    }
}

/** The repository root, passed by Gradle; tests run with the module directory as cwd. */
internal fun repoRoot(): File = File(System.getProperty("muviss.repo.root") ?: "../..")
