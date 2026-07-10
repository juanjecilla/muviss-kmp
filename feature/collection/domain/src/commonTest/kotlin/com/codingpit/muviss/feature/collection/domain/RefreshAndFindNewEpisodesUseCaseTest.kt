package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TODAY_EPOCH_DAY = 100L
private const val TODAY_EPOCH_MS = TODAY_EPOCH_DAY * 86_400_000L

private class FakeClock : AppClock {
    override fun nowEpochMs(): Long = TODAY_EPOCH_MS
}

/** Test double for [MediaSnapshotSource]: each media id resolves to a canned [MediaDetails] (or a failure). */
private class FakeMediaSnapshotSource(private val detailsByMediaId: Map<MediaId, Result<MediaDetails>>) : MediaSnapshotSource {
    override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> = detailsByMediaId[mediaId] ?: error("No fixture for $mediaId")
}

/** Test double for [CollectionRepository]: [upsertSnapshot] just records what it was called with. */
private class FakeCollectionRepository(entries: List<CollectionEntry>) : CollectionRepository {
    private val state = MutableStateFlow(entries)
    val upsertedDetails = mutableListOf<MediaDetails>()

    override fun observeAll(): Flow<List<CollectionEntry>> = state
    override fun observeEntry(mediaId: MediaId): Flow<CollectionEntry?> = error("not used")

    override suspend fun upsertSnapshot(details: MediaDetails) {
        upsertedDetails += details
    }

    override suspend fun remove(mediaId: MediaId) = error("not used")
    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = error("not used")
    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = error("not used")
}

/**
 * Exercises the actual wiring [feature.collection.api.CollectionApi.refreshAndFindNewEpisodes]
 * runs in production: read the saved library, re-fetch each title, upsert
 * the refreshed snapshot (same as `RefreshCollectionSnapshotsUseCase`), then
 * diff — proving muted-show exclusion and the fetch/upsert loop compose
 * correctly with [NewEpisodesCalculator], not just the calculator alone.
 */
class RefreshAndFindNewEpisodesUseCaseTest {

    private val severance = MediaId.tmdbTv("95396")
    private val theBear = MediaId.tmdbTv("136315")

    private fun entry(mediaId: MediaId, title: String, airedEpisodes: Int, muted: Boolean = false) = CollectionEntry(
        mediaId = mediaId,
        title = title,
        posterUrl = null,
        releaseYear = null,
        productionStatus = ProductionStatus.RETURNING,
        totalEpisodes = 10,
        airedEpisodes = airedEpisodes,
        favorite = false,
        addedAtEpochMs = 0L,
        notificationsMuted = muted,
    )

    /**
     * A show's fresh TMDB snapshot: [priorAiredCount] filler episodes that
     * already aired plus one new one at [newSeason]/[newNumber] airing
     * today — the aired *count* the use case diffs against [priorAiredCount]
     * is the sum of all of them, matching a real TMDB response's whole
     * season/episode catalog rather than just what's new.
     */
    private fun tvDetailsWithNewEpisode(mediaId: MediaId, title: String, priorAiredCount: Int, newSeason: Int, newNumber: Int): MediaDetails {
        val fillers = (1..priorAiredCount).map { number ->
            Episode(EpisodeId(mediaId, 1, number), 1, number, "Filler $number", TODAY_EPOCH_DAY - priorAiredCount + number)
        }
        val new = Episode(EpisodeId(mediaId, newSeason, newNumber), newSeason, newNumber, "New", TODAY_EPOCH_DAY)
        return MediaDetails(
            summary = MediaSummary(mediaId, title),
            productionStatus = ProductionStatus.RETURNING,
            seasons = listOf(Season(1, "S1", fillers + new)),
        )
    }

    @Test
    fun refreshes_every_saved_title_and_reports_the_ones_with_new_episodes() = runTest {
        val repository = FakeCollectionRepository(listOf(entry(severance, "Severance", airedEpisodes = 4)))
        val snapshotSource = FakeMediaSnapshotSource(
            mapOf(severance to Result.success(tvDetailsWithNewEpisode(severance, "Severance", priorAiredCount = 4, newSeason = 2, newNumber = 5))),
        )
        val useCase = RefreshAndFindNewEpisodesUseCase(repository, snapshotSource, FakeClock())

        val result = useCase()

        assertEquals(NewEpisodeNotification(severance, "Severance", newEpisodeCount = 1, latestEpisodeLabel = "S02E05"), result.single())
        assertEquals(1, repository.upsertedDetails.size) // the refreshed snapshot was persisted too.
    }

    @Test
    fun muted_shows_are_excluded_from_the_result_but_still_refreshed() = runTest {
        val repository = FakeCollectionRepository(listOf(entry(severance, "Severance", airedEpisodes = 4, muted = true)))
        val snapshotSource = FakeMediaSnapshotSource(
            mapOf(severance to Result.success(tvDetailsWithNewEpisode(severance, "Severance", priorAiredCount = 4, newSeason = 2, newNumber = 5))),
        )
        val useCase = RefreshAndFindNewEpisodesUseCase(repository, snapshotSource, FakeClock())

        val result = useCase()

        assertTrue(result.isEmpty())
        assertEquals(1, repository.upsertedDetails.size) // muting hides the notification, not the refresh itself.
    }

    @Test
    fun a_failed_fetch_for_one_show_does_not_block_the_others() = runTest {
        val repository = FakeCollectionRepository(
            listOf(entry(severance, "Severance", airedEpisodes = 4), entry(theBear, "The Bear", airedEpisodes = 2)),
        )
        val snapshotSource = FakeMediaSnapshotSource(
            mapOf(
                severance to Result.failure(IllegalStateException("network error")),
                theBear to Result.success(tvDetailsWithNewEpisode(theBear, "The Bear", priorAiredCount = 2, newSeason = 1, newNumber = 3)),
            ),
        )
        val useCase = RefreshAndFindNewEpisodesUseCase(repository, snapshotSource, FakeClock())

        val result = useCase()

        assertEquals(NewEpisodeNotification(theBear, "The Bear", newEpisodeCount = 1, latestEpisodeLabel = "S01E03"), result.single())
    }
}
