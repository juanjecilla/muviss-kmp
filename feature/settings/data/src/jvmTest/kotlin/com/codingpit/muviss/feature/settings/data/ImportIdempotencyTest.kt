@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.collection.data.SqlDelightCollectionRepository
import com.codingpit.muviss.feature.collection.domain.CollectionRepository
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.data.SqlDelightProgressRepository
import com.codingpit.muviss.feature.progress.domain.ProgressRepository
import com.codingpit.muviss.feature.settings.domain.ApplyImportUseCase
import com.codingpit.muviss.feature.settings.domain.ExternalTitleRef
import com.codingpit.muviss.feature.settings.domain.ImportMediaDetailsSource
import com.codingpit.muviss.feature.settings.domain.ImportPreview
import com.codingpit.muviss.feature.settings.domain.ImportSource
import com.codingpit.muviss.feature.settings.domain.ImportedEpisode
import com.codingpit.muviss.feature.settings.domain.ImportedTitle
import com.codingpit.muviss.feature.settings.domain.ResolvedImportTitle
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class IdempotencyDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

private class IdempotencyClock(private val millis: Long = 0L) : AppClock {
    override fun nowEpochMs(): Long = millis
}

/** [CollectionApi] wired directly over the real repository — test-only, mirrors `ProfileStatsAggregationTest`'s equivalent. */
private class RealCollectionApi(private val repository: CollectionRepository) : CollectionApi {
    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = repository.observeEntry(mediaId)
        .map { entry -> entry?.let { CollectionMembership(it.mediaId, it.favorite, it.notificationsMuted, it.rating, it.note) } }

    override fun observeSummaries(): Flow<List<CollectionSummary>> = error("not used")
    override suspend fun add(details: MediaDetails) = repository.upsertSnapshot(details)
    override suspend fun remove(mediaId: MediaId) = repository.remove(mediaId)
    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = repository.setFavorite(mediaId, favorite)
    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = repository.setNotificationsMuted(mediaId, muted)
    override suspend fun setRating(mediaId: MediaId, rating: Int?) = repository.setRating(mediaId, rating)
    override suspend fun setNote(mediaId: MediaId, note: String?) = repository.setNote(mediaId, note)
    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = error("not used")
}

/** [ProgressApi] wired directly over the real repository — test-only, mirrors `ProgressCollectionStatusIntegrationTest`'s equivalent. */
private class RealProgressApi(private val repository: ProgressRepository) : ProgressApi {
    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = repository.observeForMedia(mediaId)
        .map { rows -> rows.filter { it.seen }.map { it.episodeId }.toSet() }

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = repository.observeSeenActivityEpochDays()
    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) = repository.setSeen(episodeId, seen)
    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = repository.observePlayCounts(mediaId)
    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = repository.observePlays(episodeId)
    override suspend fun recordPlay(episodeId: EpisodeId) = repository.recordPlay(episodeId)
    override suspend fun removeLatestPlay(episodeId: EpisodeId) = repository.removeLatestPlay(episodeId)
    override suspend fun clearPlays(episodeId: EpisodeId) = repository.clearPlays(episodeId)
    override suspend fun markSeasonAiredSeen(season: Season, todayEpochDay: Long): List<EpisodeId> = error("not used")
    override suspend fun markShowAiredSeen(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> = error("not used")
    override suspend fun unmarkSeason(season: Season) = error("not used")
    override suspend fun unmarkShow(seasons: List<Season>) = error("not used")
    override suspend fun undoBulkMark(episodeIds: List<EpisodeId>) = error("not used")
    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = error("not used")

    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) = error("not used")

    override suspend fun clearProgress(mediaId: MediaId) = error("not used")
    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = repository.setSeen(EpisodeId.forMovie(mediaId), watched)
}

/** Canned [ImportMediaDetailsSource] — no real TMDB call, this test only proves the write path is idempotent. */
private class FixedDetailsSource(private val details: Map<MediaId, MediaDetails>) : ImportMediaDetailsSource {
    override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> = details[mediaId]?.let { Result.success(it) } ?: Result.failure(NoSuchElementException(mediaId.toString()))
}

/**
 * Proves EPIC 18's idempotency contract end to end against real SQLDelight
 * repositories: running the same [ApplyImportUseCase] twice never duplicates
 * a collection entry, never un-ticks a previously-seen episode, and never
 * overwrites a rating the first run (or the user) already set. See
 * `docs/IMPORT.md` "Idempotency" for the semantics this enforces.
 */
class ImportIdempotencyTest {

    private val movieId = MediaId.tmdbMovie("603")
    private val showId = MediaId.tmdbTv("1399")

    private val movieDetails = MediaDetails(
        summary = MediaSummary(movieId, "The Matrix"),
        productionStatus = ProductionStatus.RELEASED,
    )

    private fun episode(season: Int, number: Int) = Episode(
        id = EpisodeId(showId, season, number),
        seasonNumber = season,
        episodeNumber = number,
        name = "S${season}E$number",
        airDateEpochDay = 0,
    )

    private val showDetails = MediaDetails(
        summary = MediaSummary(showId, "Show"),
        productionStatus = ProductionStatus.RETURNING,
        seasons = listOf(Season(1, "S1", listOf(episode(1, 1), episode(1, 2)))),
    )

    private lateinit var collectionRepository: CollectionRepository
    private lateinit var collectionApi: CollectionApi
    private lateinit var progressApi: ProgressApi
    private lateinit var applyImport: ApplyImportUseCase

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        val db = MuvissDatabase(driver)
        val dispatchers = IdempotencyDispatchers(UnconfinedTestDispatcher())
        val clock = IdempotencyClock()

        val progressRepository = SqlDelightProgressRepository(db.episodeProgressQueries, db.episodePlayQueries, dispatchers, clock)
        progressApi = RealProgressApi(progressRepository)
        collectionRepository = SqlDelightCollectionRepository(db.collectionEntryQueries, dispatchers, clock, progressApi)
        collectionApi = RealCollectionApi(collectionRepository)

        val detailsSource = FixedDetailsSource(mapOf(movieId to movieDetails, showId to showDetails))
        applyImport = ApplyImportUseCase(detailsSource, collectionApi, progressApi)
    }

    private fun preview(vararg resolved: ResolvedImportTitle) = ImportPreview(ImportSource.TRAKT, resolved.toList(), emptyList(), 0)

    @Test
    fun re_running_the_same_import_does_not_duplicate_the_collection_entry() = runTest {
        val title = ImportedTitle(ExternalTitleRef(tmdbId = "603"), MediaType.MOVIE, "The Matrix", watched = true)

        applyImport(preview(ResolvedImportTitle(title, movieId)))
        applyImport(preview(ResolvedImportTitle(title, movieId)))

        val all = collectionRepository.observeAll()
        assertEquals(1, all.first().size)
    }

    @Test
    fun re_running_the_same_import_does_not_regress_a_watched_movie() = runTest {
        val title = ImportedTitle(ExternalTitleRef(tmdbId = "603"), MediaType.MOVIE, "The Matrix", watched = true)

        applyImport(preview(ResolvedImportTitle(title, movieId)))
        applyImport(preview(ResolvedImportTitle(title, movieId)))

        val entry = collectionRepository.observeEntry(movieId).first()
        assertEquals(1, entry?.seenEpisodes)
    }

    @Test
    fun re_running_the_same_import_does_not_duplicate_episode_ticks() = runTest {
        val title = ImportedTitle(
            ExternalTitleRef(tmdbId = "1399"),
            MediaType.TV,
            "Show",
            episodes = listOf(ImportedEpisode(1, 1), ImportedEpisode(1, 2)),
        )

        applyImport(preview(ResolvedImportTitle(title, showId)))
        val firstRunResult = applyImport(preview(ResolvedImportTitle(title, showId)))

        val entry = collectionRepository.observeEntry(showId).first()
        assertEquals(2, entry?.seenEpisodes)
        assertEquals(2, firstRunResult.episodeTickCount) // both ticks re-applied, but as idempotent upserts
    }

    @Test
    fun re_running_the_same_import_does_not_overwrite_a_rating_the_first_run_already_set() = runTest {
        val title = ImportedTitle(ExternalTitleRef(tmdbId = "603"), MediaType.MOVIE, "The Matrix", rating = 8)

        applyImport(preview(ResolvedImportTitle(title, movieId)))
        collectionApi.setRating(movieId, 5) // the user re-rates it locally between imports
        applyImport(preview(ResolvedImportTitle(title, movieId)))

        val membership = collectionApi.observeMembership(movieId).first()
        assertEquals(5, membership?.rating) // the re-import's rating (8) never clobbers the user's edit
    }

    @Test
    fun a_second_import_only_adds_what_the_first_was_missing() = runTest {
        val episode1 = ImportedEpisode(1, 1)
        val episode2 = ImportedEpisode(1, 2)
        val firstFile = ImportedTitle(ExternalTitleRef(tmdbId = "1399"), MediaType.TV, "Show", episodes = listOf(episode1))
        val secondFile = ImportedTitle(ExternalTitleRef(tmdbId = "1399"), MediaType.TV, "Show", episodes = listOf(episode1, episode2))

        applyImport(preview(ResolvedImportTitle(firstFile, showId)))
        applyImport(preview(ResolvedImportTitle(secondFile, showId)))

        val entry = collectionRepository.observeEntry(showId).first()
        assertEquals(2, entry?.seenEpisodes)
        assertTrue(entry != null)
    }
}
