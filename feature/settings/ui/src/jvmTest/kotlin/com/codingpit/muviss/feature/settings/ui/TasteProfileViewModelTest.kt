package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteKind
import com.codingpit.muviss.feature.settings.api.TasteProfileApi
import com.codingpit.muviss.feature.settings.domain.AppSettings
import com.codingpit.muviss.feature.settings.domain.GenreCatalog
import com.codingpit.muviss.feature.settings.domain.LoadGenresUseCase
import com.codingpit.muviss.feature.settings.domain.MutedGenreEntry
import com.codingpit.muviss.feature.settings.domain.ObserveSettingsUseCase
import com.codingpit.muviss.feature.settings.domain.TasteCatalog
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeTasteApi : TasteProfileApi {
    val liked = MutableStateFlow<List<TasteItem>>(emptyList())
    val calls = mutableListOf<Pair<TasteItem, Boolean>>()
    override fun observe(): Flow<List<TasteItem>> = liked
    override suspend fun setLiked(item: TasteItem, liked: Boolean): Boolean {
        calls += item to liked
        return true
    }
}

private class FakeCatalog(var failure: Throwable? = null) : GenreCatalog {
    override suspend fun genres(type: MediaType): Result<List<Genre>> = failure?.let { Result.failure(it) }
        ?: Result.success(if (type == MediaType.TV) listOf(Genre("10767", "Talk"), Genre("18", "Drama")) else listOf(Genre("28", "Action")))

    override suspend fun examples(type: MediaType, genreId: String, limit: Int): Result<List<MediaSummary>> = Result.success(emptyList())
}

/**
 * The editor's view model over fakes: what is liked, what is muted and what TMDB offers
 * combine into the chips the screen draws.
 */
class TasteProfileViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        api: TasteProfileApi,
        muted: Set<GenreKey>,
        catalog: GenreCatalog,
    ): Pair<TasteProfileViewModel, FakeRepository> {
        val repository = FakeRepository(AppSettings(mutedGenres = muted.map { MutedGenreEntry(it, "") }))
        val vm = TasteProfileViewModel(api, ObserveSettingsUseCase(repository), LoadGenresUseCase(catalog))
        return vm to repository
    }

    @Test
    fun a_liked_item_is_marked_liked_and_a_muted_genre_is_marked_muted() = runTest {
        val api = FakeTasteApi().apply { liked.value = listOf(TasteItem(TasteKind.GENRE, "movie:28", "Action")) }
        val (vm, _) = viewModel(api, setOf(GenreKey(MediaType.TV, "10767")), FakeCatalog())

        val state = vm.state.first { !it.loading }

        assertTrue(state.movieGenres.single().liked)
        assertTrue(state.tvGenres.single { it.item.value == "tv:10767" }.muted)
        assertFalse(state.tvGenres.single { it.item.value == "tv:18" }.muted)
    }

    @Test
    fun the_fixed_options_are_offered_whatever_the_network_says() = runTest {
        val (vm, _) = viewModel(FakeTasteApi(), emptySet(), FakeCatalog(MetadataError.Offline()))

        val state = vm.state.first { !it.loading }

        assertTrue(state.genresFailed)
        assertTrue(state.movieGenres.isEmpty())
        assertEquals(TasteCatalog.services.size, state.services.size)
        assertEquals(TasteCatalog.companies.size, state.companies.size)
    }

    @Test
    fun tapping_an_option_asks_the_api_for_the_opposite_of_its_state() = runTest {
        val api = FakeTasteApi()
        val (vm, _) = viewModel(api, emptySet(), FakeCatalog())
        val netflix = vm.state.first { !it.loading }.services.single { it.item.label == "Netflix" }

        vm.onToggle(netflix)

        assertEquals(listOf(netflix.item to true), api.calls)
    }

    @Test
    fun a_genre_muted_after_load_is_disabled_without_a_reload() = runTest {
        val (vm, repository) = viewModel(FakeTasteApi(), emptySet(), FakeCatalog())
        assertFalse(vm.state.first { !it.loading }.tvGenres.single { it.item.value == "tv:10767" }.muted)

        repository.settings.value = repository.settings.value.copy(mutedGenres = listOf(MutedGenreEntry(GenreKey(MediaType.TV, "10767"), "Talk")))

        assertTrue(vm.state.first { s -> s.tvGenres.any { it.muted } }.tvGenres.single { it.item.value == "tv:10767" }.muted)
    }
}
