@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.SystemClock
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.search.domain.EpisodeDetailUseCase
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.ImageKind
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** An episode's still opens the Image Viewer on the Still gallery, seeded with the still that was tapped. */
class EpisodeStillTapTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_still_is_named_has_a_button_role_and_says_what_a_tap_does() = runComposeUiTest {
        setContent { EpisodeWithStill(stillUrl = STILL) { _, _ -> } }
        waitForIdle()

        val still = onNodeWithContentDescription("Still")
        still.assertContentDescriptionEquals("Still")
        still.assertHasClickAction()
        assertEquals(Role.Button, still.fetchSemanticsNode().config[SemanticsProperties.Role])
        assertEquals("View still full screen", still.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
    }

    @Test
    fun the_still_is_named_still_while_its_picture_is_loading() = runComposeUiTest {
        // The still's request is held, so the screen shows the loading state: the state in which the picture's own
        // description (the episode title) used to be read as part of the button's name.
        val gate = CompletableDeferred<Unit>()
        val loader = installLoader(mapOf(drawnImageUrl(STILL, PosterSize.Detail)!! to Answer.Waits(gate, Size(300f, 169f))))
        try {
            setContent { EpisodeWithStill(stillUrl = STILL) { _, _ -> } }
            waitUntil(timeoutMillis = 10_000) { drawnImageUrl(STILL, PosterSize.Detail) in loader.requested }
            waitForIdle()

            onNodeWithContentDescription("Still").assertContentDescriptionEquals("Still")
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun tapping_the_still_opens_the_still_gallery_on_the_url_drawn() = runComposeUiTest {
        val opened = mutableListOf<Pair<ImageKind, String>>()
        setContent { EpisodeWithStill(stillUrl = STILL) { kind, url -> opened += kind to url } }
        waitForIdle()

        onNodeWithContentDescription("Still").performClick()

        assertEquals(listOf(ImageKind.Still to drawnImageUrl(STILL, PosterSize.Still)), opened)
    }

    private companion object {
        const val STILL = "https://image.tmdb.org/t/p/w300/still.jpg"
    }
}

@Composable
private fun EpisodeWithStill(stillUrl: String, onOpenImage: (ImageKind, String) -> Unit) {
    val id = EpisodeId(MediaId.tmdbTv("1399"), 3, 9)
    val repo = FakeEpisodeRepo(
        Result.success(EpisodeDetails(id = id, name = "The Long Night", seasonNumber = 3, episodeNumber = 9, stillUrl = stillUrl)),
    )
    val viewModel = EpisodeDetailViewModel(id, EpisodeDetailUseCase(repo), FakeProgressApi(), SystemClock())
    MuvissTheme(darkTheme = true) {
        EpisodeDetailScreen(viewModel = viewModel, onBack = {}, onOpenImage = onOpenImage)
    }
}
