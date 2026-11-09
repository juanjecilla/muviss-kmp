package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private val POSTER_MIN_WIDTH = 110.dp

/** Beyond this the columns would be wider than a phone. At 200 % text, 1 column is the right answer anyway. */
private const val MAX_FONT_SCALE = 2f

/**
 * Poster grid columns that follow the user's text size.
 *
 * Captions wrap to two lines of text that grows with the font scale: squeezed into 110 dp columns, a title at 200 %
 * breaks mid-word. Instead of shrinking the text (which the user asked for), the grid shows fewer, wider cards.
 * Found by the a11y audit with `font-scale 2.0` (docs/AUDIT.md, L1).
 */
fun posterGridCells(fontScale: Float): GridCells = GridCells.Adaptive(minSize = POSTER_MIN_WIDTH * fontScale.coerceIn(1f, MAX_FONT_SCALE))

@Composable
fun posterGridCells(): GridCells = posterGridCells(LocalDensity.current.fontScale)
