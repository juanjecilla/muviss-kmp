package com.codingpit.muviss.core.designsystem.component

import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A 411 dp phone: how many poster columns at each of the user's text sizes. */
class PosterGridCellsTest {

    private fun columns(fontScale: Float, widthDp: Int = 411): Int {
        val density = Density(density = 2.625f, fontScale = fontScale)
        val widthPx = (widthDp * density.density).toInt()
        return with(posterGridCells(fontScale)) { density.calculateCrossAxisCellSizes(widthPx, 0) }.size
    }

    @Test
    fun at_the_default_size_the_grid_is_as_before() = assertEquals(3, columns(1f))

    @Test
    fun larger_text_means_fewer_wider_columns() {
        assertTrue(columns(1.5f) < columns(1f), "1.5x should have fewer columns than 1x")
        assertTrue(columns(2f) < columns(1.5f), "2x should have fewer columns than 1.5x")
    }

    @Test
    fun smaller_than_default_text_never_adds_columns() = assertEquals(columns(1f), columns(0.85f))
}
