@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.search.domain.ExternalLinks
import com.codingpit.muviss.models.MediaAnchors
import com.codingpit.muviss.models.MediaId
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalLinksRowTest {

    private val links = ExternalLinks.forTitle(MediaId.tmdbMovie("27205"), MediaAnchors("tt1375666", "Q25188"), "en")

    @Test
    fun each_link_is_a_chip_and_tapping_it_opens_its_address() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { MuvissTheme(darkTheme = false) { ExternalLinksRow(links, open = { opened += it }) } }

        onNodeWithText("IMDb").assertIsDisplayed()
        onNodeWithText("Wikipedia").assertIsDisplayed()
        onNodeWithText("TMDB").performClick()
        onNodeWithText("IMDb").performClick()

        assertEquals(listOf("https://www.themoviedb.org/movie/27205", "https://www.imdb.com/title/tt1375666/"), opened)
    }

    @Test
    fun no_links_draws_nothing() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = false) { ExternalLinksRow(emptyList(), open = {}) } }

        onAllNodesWithTag(EXTERNAL_LINKS_TAG).assertCountEquals(0)
    }
}
