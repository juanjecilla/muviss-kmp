package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeExternalIdResolver(private val byImdb: Map<String, MediaId> = emptyMap()) : ExternalIdResolver {
    override suspend fun resolve(ref: ExternalTitleRef, type: MediaType?): MediaId? {
        ref.tmdbId?.let { tmdbId -> return if (type == MediaType.MOVIE) MediaId.tmdbMovie(tmdbId) else MediaId.tmdbTv(tmdbId) }
        return ref.imdbId?.let { byImdb[it] }
    }
}

private class FakeDetailsSource(private val details: Map<MediaId, MediaDetails> = emptyMap()) : ImportMediaDetailsSource {
    var failFor: MediaId? = null
    var failure: Throwable = RuntimeException("boom")
    override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> {
        if (mediaId == failFor) return Result.failure(failure)
        return details[mediaId]?.let { Result.success(it) } ?: Result.failure(NoSuchElementException(mediaId.toString()))
    }
}

private class FakeCollectionApi : CollectionApi {
    val added = mutableListOf<MediaDetails>()
    val ratings = mutableMapOf<MediaId, Int?>()

    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = MutableStateFlow(ratings[mediaId]?.let { CollectionMembership(mediaId, favorite = false, rating = it) })

    override fun observeSummaries(): Flow<List<CollectionSummary>> = MutableStateFlow(emptyList())
    override suspend fun add(details: MediaDetails) {
        added += details
    }

    override suspend fun remove(mediaId: MediaId) = Unit
    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = Unit
    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = Unit
    override suspend fun setRating(mediaId: MediaId, rating: Int?) {
        ratings[mediaId] = rating
    }

    override suspend fun setNote(mediaId: MediaId, note: String?) = Unit

    override suspend fun setRevisitWillingness(mediaId: MediaId, willing: Boolean?) = error("not used")

    override suspend fun setCoWatchPinned(mediaId: MediaId, pinned: Boolean) = error("not used")
    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = emptyList()
}

private class FakeProgressApi : ProgressApi {
    val seenEpisodes = mutableSetOf<EpisodeId>()
    val watchedMovies = mutableSetOf<MediaId>()

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = MutableStateFlow(emptySet())

    // Watch-next (EPIC 22) — this fake's subject never asks for it.
    override fun observeWatchNext(): Flow<List<WatchNextItem>> = flowOf(emptyList())
    override suspend fun refreshWatchNextCatalogs() = Unit

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = MutableStateFlow(emptySet())
    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) {
        if (seen) seenEpisodes += episodeId else seenEpisodes -= episodeId
    }

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
    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) {
        if (watched) watchedMovies += mediaId else watchedMovies -= mediaId
    }
}

class PreviewImportUseCaseTest {

    private val movieId = MediaId.tmdbMovie("603")
    private val showId = MediaId.tmdbTv("1399")

    private fun useCase(resolver: ExternalIdResolver = FakeExternalIdResolver()) = PreviewImportUseCase(listOf(GenericCsvImportParser(), TvTimeImportParser(), TraktImportParser()), resolver)

    @Test
    fun resolves_rows_with_a_tmdb_id_directly() = runTest {
        val csv = "title,type,tmdb_id\nThe Matrix,movie,603\n"

        val preview = useCase().invoke(csv)

        assertEquals(1, preview.resolved.size)
        assertEquals(movieId, preview.resolved.single().mediaId)
        assertTrue(preview.unresolved.isEmpty())
    }

    @Test
    fun resolves_imdb_only_rows_through_the_resolver() = runTest {
        val csv = "title,type,imdb_id\nGame of Thrones,tv,tt0944947\n"
        val resolver = FakeExternalIdResolver(byImdb = mapOf("tt0944947" to showId))

        val preview = useCase(resolver).invoke(csv)

        assertEquals(showId, preview.resolved.single().mediaId)
    }

    @Test
    fun a_row_with_no_ids_is_unresolved_with_a_reason() = runTest {
        val csv = "title,type\nUnknown Movie,movie\n"

        val preview = useCase().invoke(csv)

        assertTrue(preview.resolved.isEmpty())
        val unresolved = preview.unresolved.single()
        assertEquals(UnresolvedReason.NoExternalId, unresolved.reason)
    }

    @Test
    fun an_id_tmdb_does_not_know_is_unresolved_as_no_match() = runTest {
        val csv = "title,type,imdb_id\nLost Film,movie,tt0000001\n"

        val preview = useCase().invoke(csv)

        assertEquals(UnresolvedReason.NoMatch, preview.unresolved.single().reason)
    }

    @Test
    fun counts_reflect_titles_and_episodes_across_both_buckets() = runTest {
        val csv = "title,type,tmdb_id,season,episode\n" +
            "Show,tv,1399,1,1\n" +
            "Show,tv,1399,1,2\n"

        val preview = useCase().invoke(csv)

        assertEquals(1, preview.titleCount)
        assertEquals(2, preview.episodeCount)
        assertEquals(0, preview.unresolvedCount)
    }

    @Test
    fun reports_progress_once_per_title() = runTest {
        val csv = "title,type,tmdb_id\nA,movie,1\nB,movie,2\n"
        val calls = mutableListOf<Pair<Int, Int>>()

        useCase().invoke(csv) { done, total -> calls += done to total }

        assertEquals(listOf(1 to 2, 2 to 2), calls)
    }

    @Test
    fun unrecognized_content_throws() = runTest {
        val e = assertFailsWith<ImportFileException> { useCase().invoke("not a recognized format at all") }
        assertEquals(ImportFileError.Unrecognized, e.kind)
    }
}

class ApplyImportUseCaseTest {

    private val movieId = MediaId.tmdbMovie("603")
    private val showId = MediaId.tmdbTv("1399")
    private val movieDetails = MediaDetails(summary = MediaSummary(movieId, "The Matrix"))
    private val showDetails = MediaDetails(
        summary = MediaSummary(showId, "Show"),
        seasons = listOf(Season(1, "S1", emptyList())),
    )

    private fun preview(vararg resolved: ResolvedImportTitle, unresolved: List<UnresolvedImportTitle> = emptyList()) = ImportPreview(ImportSource.GENERIC_CSV, resolved.toList(), unresolved, skippedRowCount = 0)

    @Test
    fun imports_a_watched_movie_and_ticks_it() = runTest {
        val collectionApi = FakeCollectionApi()
        val progressApi = FakeProgressApi()
        val useCase = ApplyImportUseCase(FakeDetailsSource(mapOf(movieId to movieDetails)), collectionApi, progressApi)
        val title = ImportedTitle(ExternalTitleRef(tmdbId = "603"), MediaType.MOVIE, "The Matrix", watched = true)

        val result = useCase(preview(ResolvedImportTitle(title, movieId)))

        assertEquals(1, result.importedTitleCount)
        assertEquals(1, result.episodeTickCount)
        assertTrue(movieId in progressApi.watchedMovies)
        assertEquals(1, collectionApi.added.size)
    }

    @Test
    fun ticks_every_imported_episode_for_a_show() = runTest {
        val collectionApi = FakeCollectionApi()
        val progressApi = FakeProgressApi()
        val useCase = ApplyImportUseCase(FakeDetailsSource(mapOf(showId to showDetails)), collectionApi, progressApi)
        val title = ImportedTitle(
            ExternalTitleRef(tmdbId = "1399"),
            MediaType.TV,
            "Show",
            episodes = listOf(ImportedEpisode(1, 1), ImportedEpisode(1, 2)),
        )

        val result = useCase(preview(ResolvedImportTitle(title, showId)))

        assertEquals(2, result.episodeTickCount)
        assertEquals(setOf(EpisodeId(showId, 1, 1), EpisodeId(showId, 1, 2)), progressApi.seenEpisodes)
    }

    @Test
    fun sets_rating_only_when_none_exists_yet() = runTest {
        val collectionApi = FakeCollectionApi()
        collectionApi.ratings[movieId] = 5 // user already rated it locally
        val progressApi = FakeProgressApi()
        val useCase = ApplyImportUseCase(FakeDetailsSource(mapOf(movieId to movieDetails)), collectionApi, progressApi)
        val title = ImportedTitle(ExternalTitleRef(tmdbId = "603"), MediaType.MOVIE, "The Matrix", rating = 9)

        useCase(preview(ResolvedImportTitle(title, movieId)))

        assertEquals(5, collectionApi.ratings[movieId]) // untouched, not overwritten by the import's rating
    }

    @Test
    fun a_details_fetch_failure_is_reported_not_thrown() = runTest {
        val collectionApi = FakeCollectionApi()
        val progressApi = FakeProgressApi()
        val source = FakeDetailsSource(mapOf(movieId to movieDetails))
        source.failFor = movieId
        val useCase = ApplyImportUseCase(source, collectionApi, progressApi)
        val title = ImportedTitle(ExternalTitleRef(tmdbId = "603"), MediaType.MOVIE, "The Matrix", watched = true)

        val result = useCase(preview(ResolvedImportTitle(title, movieId)))

        assertEquals(0, result.importedTitleCount)
        assertEquals(1, result.failed.size)
        // Not a MetadataError: reported as Unknown, its own text never reaches the UI (#219).
        assertIs<MetadataError.Unknown>(result.failed.single().error)
        assertTrue(collectionApi.added.isEmpty())
    }

    @Test
    fun a_network_failure_keeps_its_kind_so_the_ui_can_say_offline() = runTest {
        val source = FakeDetailsSource(mapOf(movieId to movieDetails))
        source.failFor = movieId
        source.failure = MetadataError.Offline()
        val useCase = ApplyImportUseCase(source, FakeCollectionApi(), FakeProgressApi())
        val title = ImportedTitle(ExternalTitleRef(tmdbId = "603"), MediaType.MOVIE, "The Matrix")

        val result = useCase(preview(ResolvedImportTitle(title, movieId)))

        assertIs<MetadataError.Offline>(result.failed.single().error)
    }

    @Test
    fun unresolved_titles_pass_through_into_the_result_untouched() = runTest {
        val collectionApi = FakeCollectionApi()
        val progressApi = FakeProgressApi()
        val useCase = ApplyImportUseCase(FakeDetailsSource(), collectionApi, progressApi)
        val unresolvedTitle = UnresolvedImportTitle(
            ImportedTitle(ExternalTitleRef(), MediaType.MOVIE, "Unknown"),
            reason = UnresolvedReason.NoExternalId,
        )

        val result = useCase(preview(unresolved = listOf(unresolvedTitle)))

        assertEquals(1, result.unresolved.size)
        assertEquals(0, result.importedTitleCount)
    }
}
