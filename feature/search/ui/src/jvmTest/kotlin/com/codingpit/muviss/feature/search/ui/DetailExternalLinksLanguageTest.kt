@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.MoreLikeThisUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SimilarMediaUseCase
import com.codingpit.muviss.feature.search.domain.WatchProvidersUseCase
import com.codingpit.muviss.models.MediaAnchors
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Detail screen's Wikipedia chip follows the app language it is given (issue #996 review). A movie
 * keeps the list short, so the links row is one scroll away from the top.
 */
class DetailExternalLinksLanguageTest {

    private val movie = MediaId.tmdbMovie("27205")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun the_detail_wikipedia_link_opens_in_the_app_language_given() {
        val cases = listOf("es-ES" to "eswiki", "ja-JP" to "jawiki", "en-US" to "enwiki")
        for ((language, edition) in cases) {
            val opened = mutableListOf<String>()
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalUriHandler provides recordingUriHandler(opened)) {
                        MuvissTheme(darkTheme = false) {
                            DetailScreen(viewModel = movieViewModel(), onBack = {}, onOpenDetail = {}, onOpenEpisode = {}, linkLanguage = language)
                        }
                    }
                }
                waitForIdle()

                onNodeWithTag(DETAIL_LIST_TAG).performScrollToKey("links")
                onNodeWithText("Wikipedia").performClick()
            }

            assertEquals("https://www.wikidata.org/wiki/Special:GoToLinkedPage/$edition/Q25188", opened.single(), "language $language")
        }
    }

    @Test
    fun a_detail_screen_given_no_language_opens_the_english_wikipedia() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalUriHandler provides recordingUriHandler(opened)) {
                MuvissTheme(darkTheme = false) {
                    DetailScreen(viewModel = movieViewModel(), onBack = {}, onOpenDetail = {}, onOpenEpisode = {})
                }
            }
        }
        waitForIdle()

        onNodeWithTag(DETAIL_LIST_TAG).performScrollToKey("links")
        onNodeWithText("Wikipedia").performClick()

        assertEquals("https://www.wikidata.org/wiki/Special:GoToLinkedPage/enwiki/Q25188", opened.single())
    }

    private fun movieViewModel(): DetailViewModel {
        val details = MediaDetails(
            summary = MediaSummary(id = movie, title = "Links Movie", year = 2010, overview = "A film.", rating = 7.0),
            anchors = MediaAnchors(imdbId = "tt1375666", wikidataId = "Q25188"),
        )
        val repo = FakeDetailRepo(details = details)
        val collection = FakeCollectionApi().apply { runBlocking { add(details) } }
        return DetailViewModel(
            movie,
            MediaDetailUseCase(repo),
            DetailPeers(collection, FakeProgressApi(), FakeTriageApi(), FakeMutingApi()),
            WatchProvidersUseCase(repo),
            MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
            LinksDayClock(),
        )
    }

    private fun recordingUriHandler(opened: MutableList<String>) = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }
}

private class LinksDayClock : AppClock {
    override fun nowEpochMs(): Long = 20_000L * 86_400_000L
}
