@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.feature.settings.domain.TmdbDataSource
import kotlin.test.Test

/** Both states of the TMDB data source row, in both themes (EPIC 46). */
class TmdbKeyRowGoldenTest {

    @Test
    fun tmdb_key_row_light() = runComposeUiTest {
        setContent { Rows(darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("tmdb-key-row", tolerance = ROW_TOLERANCE)
    }

    @Test
    fun tmdb_key_row_dark() = runComposeUiTest {
        setContent { Rows(darkTheme = true) }
        waitForIdle()
        assertMatchesGolden("tmdb-key-row-dark", tolerance = ROW_TOLERANCE)
    }
}

@Composable
private fun Rows(darkTheme: Boolean) {
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface {
            Column(Modifier.padding(16.dp)) {
                TmdbKeyRow(TmdbKeyUiState(visible = true, source = TmdbDataSource.MUVISS), { _, _ -> }, {}, {})
                TmdbKeyRow(TmdbKeyUiState(visible = true, source = TmdbDataSource.YOURS), { _, _ -> }, {}, {})
            }
        }
    }
}

// Two short text rows on a plain surface: glyph edges are the only thing that can differ between
// macOS and Linux, and there are few of them. Not yet measured on CI; widen with the measured value if it fails.
private const val ROW_TOLERANCE = 0.02
