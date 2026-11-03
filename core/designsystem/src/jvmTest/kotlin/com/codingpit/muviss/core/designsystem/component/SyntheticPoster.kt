package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * Stand-ins for poster artwork, because real posters are not in the repo
 * (licence and size). [lightest] is the brightest colour the artwork can show
 * anywhere, which is the case the scrim has to hold for.
 *
 * [Checker] is the adversarial one: a two-tone pattern at a 2 px period, so
 * every glyph of the badge sits over both tones. The scrim should make it no
 * worse than [White], and the rendered measurement checks that it is not.
 */
enum class SyntheticPoster(val lightest: Color) {
    White(Color.White),
    MidGrey(Color(0xFF808080)),
    Black(Color.Black),
    Checker(Color.White),
}

/** Paints [poster] behind its content. Pattern cells are two device pixels wide. */
internal fun Modifier.syntheticPoster(poster: SyntheticPoster): Modifier = when (poster) {
    SyntheticPoster.White -> background(Color.White)
    SyntheticPoster.MidGrey -> background(Color(0xFF808080))
    SyntheticPoster.Black -> background(Color.Black)
    SyntheticPoster.Checker -> drawBehind { drawChecker() }
}

private fun DrawScope.drawChecker() {
    drawRect(Color.White)
    val cell = 2.dp.toPx()
    val columns = ceil(size.width / cell).toInt()
    val rows = ceil(size.height / cell).toInt()
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            if ((row + column) % 2 == 1) {
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(column * cell, row * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}
