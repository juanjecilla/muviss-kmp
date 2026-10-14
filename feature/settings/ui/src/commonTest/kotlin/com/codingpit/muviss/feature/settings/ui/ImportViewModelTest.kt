@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.resolveAsync
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.feature.settings.domain.ApplyImportUseCase
import com.codingpit.muviss.feature.settings.domain.BackupRestorer
import com.codingpit.muviss.feature.settings.domain.BackupSummary
import com.codingpit.muviss.feature.settings.domain.ExternalIdResolver
import com.codingpit.muviss.feature.settings.domain.ExternalTitleRef
import com.codingpit.muviss.feature.settings.domain.GenericCsvImportParser
import com.codingpit.muviss.feature.settings.domain.ImportActions
import com.codingpit.muviss.feature.settings.domain.ImportMediaDetailsSource
import com.codingpit.muviss.feature.settings.domain.PreviewImportUseCase
import com.codingpit.muviss.feature.settings.domain.RestoreResult
import com.codingpit.muviss.feature.settings.ui.generated.resources.Res
import com.codingpit.muviss.feature.settings.ui.generated.resources.import_file_unrecognized
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

    override suspend fun setRevisitWillingness(mediaId: MediaId, willing: Boolean?) = error("not used")

    override suspend fun setCoWatchPinned(mediaId: MediaId, pinned: Boolean) = error("not used")
    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = emptyList()
}

private class FakeProgressApi : ProgressApi {
    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = MutableStateFlow(emptySet())

    // Watch-next (EPIC 22) — this fake's subject never asks for it.
    override fun observeWatchNext(): Flow<List<WatchNextItem>> = flowOf(emptyList())
    override suspend fun refreshWatchNextCatalogs() = Unit

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

private class FakeRestorer : BackupRestorer {
    var restored: String? = null
        private set

    override suspend fun summarize(content: String) = BackupSummary(formatVersion = 2, exportedAtEpochMs = 0L, titleCount = 3, episodeCount = 10, listCount = 1)

    override suspend fun restore(content: String): RestoreResult {
        restored = content
        return RestoreResult(restored = 14, kept = 0)
    }
}

class ImportViewModelTest {

    private val restorer = FakeRestorer()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): ImportViewModel {
        val actions = ImportActions(
            PreviewImportUseCase(listOf(GenericCsvImportParser()), FakeResolver()),
            ApplyImportUseCase(FakeDetailsSource(), FakeCollectionApi(), FakeProgressApi()),
            restorer,
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
        // The domain says which problem; the screen words it in the user's language (#219).
        assertEquals(UiText.Resource(Res.string.import_file_unrecognized), vm.state.value.error)
        assertTrue(vm.state.value.error?.resolveAsync().orEmpty().startsWith("This file isn't a format"))
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

    @Test
    fun a_muviss_backup_is_previewed_for_restore_not_parsed_as_trakt() = runTest {
        val vm = viewModel()
        val backup = """{"formatVersion":2,"exportedAtEpochMs":0,"collection":[],"progress":[]}"""

        vm.onFilePicked("muviss-backup-2026-10-07.json", backup)
        advanceUntilIdle()

        val step = vm.state.value.step
        assertTrue(step is ImportStep.BackupPreview, "was $step")
        assertEquals(3, step.summary.titleCount)
        assertEquals(null, restorer.restored, "nothing is written before the person confirms")

        vm.confirmRestore()
        advanceUntilIdle()

        assertEquals(backup, restorer.restored)
        assertEquals(ImportStep.Restored(RestoreResult(restored = 14, kept = 0)), vm.state.value.step)
    }
}
