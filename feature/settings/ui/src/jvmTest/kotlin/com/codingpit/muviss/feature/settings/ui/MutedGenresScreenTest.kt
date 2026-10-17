@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.feature.settings.domain.GenreCatalog
import com.codingpit.muviss.feature.settings.domain.LoadGenresUseCase
import com.codingpit.muviss.feature.settings.domain.ObserveSettingsUseCase
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeGenres(
    private val tv: List<Genre> = listOf(Genre("10767", "Talk"), Genre("10763", "News"), Genre("18", "Drama")),
    private val movies: List<Genre> = listOf(Genre("99", "Documentary"), Genre("28", "Action")),
    var failure: Throwable? = null,
) : GenreCatalog {
    override suspend fun genres(type: MediaType): Result<List<Genre>> = failure?.let { Result.failure(it) } ?: Result.success(if (type == MediaType.TV) tv else movies)

    override suspend fun examples(type: MediaType, genreId: String, limit: Int) = Result.success(emptyList<MediaSummary>())
}

/** Settings > Muted genres (ADR 0031): the real view model over the same fakes as [CrashReportsSettingTest]. */
class MutedGenresScreenTest {
    private val original = Locale.getDefault()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        Locale.setDefault(original)
    }

    private fun viewModel(repository: FakeRepository = FakeRepository(), genres: FakeGenres = FakeGenres()): MutedGenresViewModel {
        val actions = settingsActions(repository)
        return MutedGenresViewModel(ObserveSettingsUseCase(repository), actions, LoadGenresUseCase(genres))
    }

    @Test
    fun it_lists_the_default_muted_genres_ticked_and_the_catalogue_below() = runComposeUiTest {
        val vm = viewModel()
        setContent { MuvissTheme(darkTheme = false) { MutedGenresScreen(vm) } }

        val talk = GenreOption(GenreKey(MediaType.TV, "10767"), "Talk", true)
        onNodeWithTag(mutedGenreTag(talk)).assertIsOn()
        onNodeWithTag(availableGenreTag(talk)).assertIsOn()
        onNodeWithTag(availableGenreTag(GenreOption(GenreKey(MediaType.TV, "18"), "Drama", false))).assertIsOff()
        assertEquals(setOf("10767", "10763", "10764"), vm.state.value.muted.map { it.key.id }.toSet())
        assertTrue(vm.state.value.available.first { it.key.id == "10767" }.muted)
        assertFalse(vm.state.value.available.first { it.key.id == "18" }.muted)
    }

    @Test
    fun tapping_a_genre_row_mutes_and_unmutes_it() = runTest {
        val vm = viewModel()
        val drama = vm.state.value.available.first { it.key.id == "18" }

        vm.onToggle(drama)
        assertTrue(vm.state.value.available.first { it.key.id == "18" }.muted)
        assertTrue(vm.state.value.muted.any { it.key.id == "18" })

        vm.onToggle(vm.state.value.available.first { it.key.id == "18" })
        assertFalse(vm.state.value.muted.any { it.key.id == "18" })
    }

    @Test
    fun switching_to_movies_lists_movie_genres_and_keeps_tv_ids_separate() = runTest {
        val vm = viewModel()

        vm.browse(MediaType.MOVIE)

        assertEquals(setOf("99", "28"), vm.state.value.available.map { it.key.id }.toSet())
        assertTrue(vm.state.value.available.all { it.key.type == MediaType.MOVIE && !it.muted })
    }

    @Test
    fun muting_a_movie_genre_does_not_mute_the_tv_genre_with_the_same_id() = runTest {
        val vm = viewModel(genres = FakeGenres(tv = listOf(Genre("99", "Some TV genre")), movies = listOf(Genre("99", "Documentary"))))
        vm.browse(MediaType.MOVIE)

        vm.onToggle(vm.state.value.available.single())
        vm.browse(MediaType.TV)

        assertFalse(vm.state.value.available.single().muted)
    }

    @Test
    fun offline_the_muted_list_still_renders_and_the_catalogue_offers_a_retry() = runTest {
        val genres = FakeGenres(failure = MetadataError.Offline())
        val vm = viewModel(genres = genres)

        assertTrue(vm.state.value.availableFailed)
        assertEquals(setOf("10767", "10763", "10764"), vm.state.value.muted.map { it.key.id }.toSet())

        genres.failure = null
        vm.retry()
        assertFalse(vm.state.value.availableFailed)
        assertTrue(vm.state.value.available.isNotEmpty())
    }

    @Test
    fun the_search_switch_is_stored() = runComposeUiTest {
        val repository = FakeRepository()
        val vm = viewModel(repository)
        setContent { MuvissTheme(darkTheme = false) { MutedGenresScreen(vm) } }

        onNodeWithText("Also hide in search").performClick()

        assertTrue(repository.settings.value.mutedGenresInSearch)
    }

    @Test
    fun the_screen_says_so_when_nothing_is_muted() = runComposeUiTest {
        val repository = FakeRepository(com.codingpit.muviss.feature.settings.domain.AppSettings(mutedGenres = emptyList()))
        setContent { MuvissTheme(darkTheme = false) { MutedGenresScreen(viewModel(repository)) } }

        onNodeWithText("Nothing is muted").assertExists()
    }
}

class MutedGenresGoldenTest {
    private val original = Locale.getDefault()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        Locale.setDefault(original)
    }

    private val state = MutedGenresUiState(
        loading = false,
        muted = listOf(
            GenreOption(GenreKey(MediaType.TV, "10767"), "Talk", true),
            GenreOption(GenreKey(MediaType.TV, "10763"), "News", true),
            GenreOption(GenreKey(MediaType.TV, "10764"), "Reality", true),
        ),
        available = listOf(
            GenreOption(GenreKey(MediaType.TV, "10767"), "Talk", true),
            GenreOption(GenreKey(MediaType.TV, "10763"), "News", true),
            GenreOption(GenreKey(MediaType.TV, "18"), "Drama", false),
        ),
        loadingAvailable = false,
    )

    @Composable
    private fun Frame(darkTheme: Boolean) {
        MuvissTheme(darkTheme = darkTheme) {
            GoldenSurface { MutedGenresContent(state, onToggle = {}, onBrowse = {}, onRetry = {}, onInSearchToggled = {}) }
        }
    }

    @Test
    fun muted_genres() = runComposeUiTest {
        setContent { Frame(darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("muted-genres-screen", tolerance = TOLERANCE)
    }

    @Test
    fun muted_genres_dark() = runComposeUiTest {
        setContent { Frame(darkTheme = true) }
        waitForIdle()
        assertMatchesGolden("muted-genres-screen-dark", tolerance = TOLERANCE)
    }

    @Test
    fun muted_genres_in_spanish() = runComposeUiTest {
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        setContent { Frame(darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("muted-genres-screen-es", tolerance = TOLERANCE)
    }
}

/** Text from edge to edge; same reasoning and headroom as `SETTINGS_TOLERANCE`. */
private const val TOLERANCE = 0.04
