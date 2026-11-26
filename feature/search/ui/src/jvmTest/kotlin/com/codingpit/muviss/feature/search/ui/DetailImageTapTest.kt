@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.MoreLikeThisUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SimilarMediaUseCase
import com.codingpit.muviss.feature.search.domain.WatchProvidersUseCase
import com.codingpit.muviss.models.ImageKind
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Detail's artwork opens the Image Viewer, seeded with the very image that was tapped. */
class DetailImageTapTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun tapping_the_backdrop_opens_the_backdrop_gallery() = runComposeUiTest {
        val opened = mutableListOf<Pair<ImageKind, String>>()
        setContent { Detail(withArt = true) { kind, url -> opened += kind to url } }
        waitForIdle()

        onNodeWithTag(DETAIL_BACKDROP_TAG).performClick()

        assertEquals(listOf(ImageKind.Backdrop to BACKDROP), opened)
    }

    @Test
    fun tapping_the_poster_opens_the_poster_gallery() = runComposeUiTest {
        val loader = installLoader(emptyMap())
        val opened = mutableListOf<Pair<ImageKind, String>>()
        setContent { Detail(withArt = true) { kind, url -> opened += kind to url } }
        waitForIdle()
        // The load fails and `PosterImage` swaps the picture for its title text. A click that lands mid-swap goes to
        // a node that is gone, so wait for the settled one.
        waitForPosterSettled(loader)

        onNodeWithTag(DETAIL_POSTER_TAG).performClick()

        // The compact poster draws the w342 grid request, so the viewer opens on that same URL: it is cached, and
        // the picture is on screen offline. The stored w500 is a different cache entry.
        assertEquals(listOf(ImageKind.Poster to drawnImageUrl(POSTER, PosterSize.Grid)), opened)
    }

    @Test
    fun the_backdrop_button_has_a_name_a_role_and_says_what_a_tap_does() = runComposeUiTest {
        setContent { Detail(withArt = true) { _, _ -> } }
        waitForIdle()

        val backdrop = onNodeWithTag(DETAIL_BACKDROP_TAG)
        backdrop.assertContentDescriptionEquals("Backdrop")
        backdrop.assertHasClickAction()
        assertEquals(Role.Button, backdrop.fetchSemanticsNode().config[SemanticsProperties.Role])
        // The picture under the button has no name of its own, so the action label is what TalkBack reads after the name.
        assertEquals("View backdrop full screen", backdrop.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
    }

    @Test
    fun the_poster_button_has_a_name_and_keeps_its_action_label() = runComposeUiTest {
        val loader = installLoader(emptyMap())
        setContent { Detail(withArt = true) { _, _ -> } }
        waitForIdle()
        waitForPosterSettled(loader)

        val poster = onNodeWithTag(DETAIL_POSTER_TAG)
        poster.assertContentDescriptionEquals("Poster")
        assertEquals("View poster full screen", poster.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
    }

    @Test
    fun artwork_the_source_does_not_have_opens_nothing() = runComposeUiTest {
        val opened = mutableListOf<Pair<ImageKind, String>>()
        setContent { Detail(withArt = false) { kind, url -> opened += kind to url } }
        waitForIdle()

        onNodeWithTag(DETAIL_BACKDROP_TAG).performClick()
        onNodeWithTag(DETAIL_POSTER_TAG).performClick()

        assertEquals(emptyList(), opened)
    }

    /** Waits until the poster's request has failed, so the picture has been swapped for its fallback. */
    private fun ComposeUiTest.waitForPosterSettled(loader: ScriptedFetcherFactory) {
        waitUntil(timeoutMillis = 5_000) { loader.finished.any { it.contains("/poster.jpg") } }
        waitForIdle()
    }

    private companion object {
        const val BACKDROP = "https://image.tmdb.org/t/p/w780/backdrop.jpg"
        const val POSTER = "https://image.tmdb.org/t/p/w500/poster.jpg"
    }
}

@Composable
private fun Detail(withArt: Boolean, onOpenImage: (ImageKind, String) -> Unit) {
    val id = MediaId.tmdbMovie("603")
    val details = MediaDetails(
        summary = MediaSummary(id = id, title = "The Matrix", year = 1999, posterUrl = if (withArt) "https://image.tmdb.org/t/p/w500/poster.jpg" else null),
        backdropUrl = if (withArt) "https://image.tmdb.org/t/p/w780/backdrop.jpg" else null,
    )
    val repo = FakeDetailRepo(details = details)
    val viewModel = DetailViewModel(
        id,
        MediaDetailUseCase(repo),
        DetailPeers(FakeCollectionApi(), FakeProgressApi(), FakeTriageApi(), FakeMutingApi()),
        WatchProvidersUseCase(repo),
        MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
        object : AppClock {
            override fun nowEpochMs(): Long = 20_000L * 86_400_000L
        },
    )
    MuvissTheme(darkTheme = true) {
        GoldenSurface { DetailScreen(viewModel = viewModel, onBack = {}, onOpenDetail = {}, onOpenImage = onOpenImage) }
    }
}
