@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.settings.domain.ApplyImportUseCase
import com.codingpit.muviss.feature.settings.domain.ExternalIdResolver
import com.codingpit.muviss.feature.settings.domain.ExternalTitleRef
import com.codingpit.muviss.feature.settings.domain.GenericCsvImportParser
import com.codingpit.muviss.feature.settings.domain.ImportActions
import com.codingpit.muviss.feature.settings.domain.ImportMediaDetailsSource
import com.codingpit.muviss.feature.settings.domain.PreviewImportUseCase
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeResolver : ExternalIdResolver {
    override suspend fun resolve(ref: ExternalTitleRef, type: MediaType?): MediaId? = ref.tmdbId?.let { MediaId.tmdbMovie(it) }
}

private class FakeDetailsSource : ImportMediaDetailsSource {
    override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> = Result.success(MediaDetails(summary = MediaSummary(mediaId, "Title")))
}

private class FakeCollectionApi : CollectionApi {
    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = MutableStateFlow(null)
    override fun observeSummaries(): Flow<List<CollectionSummary>> = MutableStateFlow(emptyList())
    override suspend fun add(details: MediaDetails) = Unit
    override suspend fun remove(mediaId: MediaId) = Unit
    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = Unit
    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = Unit
    override suspend fun setRating(mediaId: MediaId, rating: Int?) = Unit
    override suspend fun setNote(mediaId: MediaId, note: String?) = Unit
    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = emptyList()
}

private class FakeProgressApi : ProgressApi {
    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = MutableStateFlow(emptySet())
    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = MutableStateFlow(emptySet())
    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) = Unit
    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = flowOf(emptyMap())
    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = flowOf(emptyList())

    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = flowOf(emptyMap())

    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = flowOf(emptyList())
    override suspend fun recordPlay(episodeId: EpisodeId) = Unit
    override suspend fun removeLatestPlay(episodeId: EpisodeId) = Unit
    override suspend fun clearPlays(episodeId: EpisodeId) = Unit
    override suspend fun markSeasonAiredSeen(season: Season, todayEpochDay: Long): List<EpisodeId> = emptyList()
    override suspend fun markShowAiredSeen(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> = emptyList()
    override suspend fun unmarkSeason(season: Season) = Unit
    override suspend fun unmarkShow(seasons: List<Season>) = Unit
    override suspend fun undoBulkMark(episodeIds: List<EpisodeId>) = Unit
    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = Unit

    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) = Unit

    override suspend fun clearProgress(mediaId: MediaId) = Unit
    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = Unit
}

class ImportViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): ImportViewModel {
        val actions = ImportActions(
            PreviewImportUseCase(listOf(GenericCsvImportParser()), FakeResolver()),
            ApplyImportUseCase(FakeDetailsSource(), FakeCollectionApi(), FakeProgressApi()),
        )
        return ImportViewModel(actions)
    }

    @Test
    fun starts_on_the_pick_file_step() = runTest {
        val vm = viewModel()
        assertIs<ImportStep.PickFile>(vm.state.value.step)
    }

    @Test
    fun onFilePicked_resolves_and_lands_on_preview() = runTest {
        val vm = viewModel()

        vm.onFilePicked("import.csv", "title,type,tmdb_id\nThe Matrix,movie,603\n")
        advanceUntilIdle()

        val step = assertIs<ImportStep.Preview>(vm.state.value.step)
        assertEquals("import.csv", step.fileName)
        assertEquals(1, step.preview.resolved.size)
    }

    @Test
    fun unrecognized_file_surfaces_an_error_and_returns_to_pick_file() = runTest {
        val vm = viewModel()

        vm.onFilePicked("mystery.txt", "not a recognized format")
        advanceUntilIdle()

        assertIs<ImportStep.PickFile>(vm.state.value.step)
        assertTrue(vm.state.value.error != null)
    }

    @Test
    fun confirmApply_moves_from_preview_to_summary() = runTest {
        val vm = viewModel()
        vm.onFilePicked("import.csv", "title,type,tmdb_id\nThe Matrix,movie,603\n")
        advanceUntilIdle()

        vm.confirmApply()
        advanceUntilIdle()

        val step = assertIs<ImportStep.Summary>(vm.state.value.step)
        assertEquals(1, step.result.importedTitleCount)
    }

    @Test
    fun cancelPreview_returns_to_pick_file_and_discards_the_preview() = runTest {
        val vm = viewModel()
        vm.onFilePicked("import.csv", "title,type,tmdb_id\nThe Matrix,movie,603\n")
        advanceUntilIdle()

        vm.cancelPreview()

        assertIs<ImportStep.PickFile>(vm.state.value.step)

        // confirmApply is now a no-op — there's no preview left to apply.
        vm.confirmApply()
        advanceUntilIdle()
        assertIs<ImportStep.PickFile>(vm.state.value.step)
    }

    @Test
    fun startOver_from_summary_returns_to_pick_file() = runTest {
        val vm = viewModel()
        vm.onFilePicked("import.csv", "title,type,tmdb_id\nThe Matrix,movie,603\n")
        advanceUntilIdle()
        vm.confirmApply()
        advanceUntilIdle()

        vm.startOver()

        assertIs<ImportStep.PickFile>(vm.state.value.step)
    }
}
