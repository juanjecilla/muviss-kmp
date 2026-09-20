@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.concurrency.REFRESH_CONCURRENCY
import com.codingpit.muviss.core.common.concurrency.mapBounded
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Operation-count budgets for bulk refresh (EPIC 27): the cap on in-flight
 * fetches is asserted as a number, on virtual time, so it never depends on how
 * fast the machine running the tests is.
 */
class BoundedRefreshTest {

    private class CountingSource : MediaSnapshotSource {
        var inFlight = 0
        var peak = 0
        var fetches = 0

        override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> {
            fetches++
            inFlight++
            peak = maxOf(peak, inFlight)
            delay(50) // virtual time: lets every permitted fetch overlap
            inFlight--
            return Result.success(MediaDetails(MediaSummary(mediaId, mediaId.toString()), productionStatus = ProductionStatus.RETURNING))
        }
    }

    private class Repository(entries: List<CollectionEntry>) : CollectionRepository {
        private val state = MutableStateFlow(entries)
        var upserts = 0

        override fun observeAll(): Flow<List<CollectionEntry>> = state
        override fun observeEntry(mediaId: MediaId): Flow<CollectionEntry?> = error("not used")
        override suspend fun upsertSnapshot(details: MediaDetails) {
            upserts++
        }

        override suspend fun remove(mediaId: MediaId) = error("not used")
        override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = error("not used")
        override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = error("not used")
        override suspend fun setRating(mediaId: MediaId, rating: Int?) = error("not used")
        override suspend fun setNote(mediaId: MediaId, note: String?) = error("not used")
    }

    private fun entry(id: MediaId) = CollectionEntry(
        mediaId = id,
        title = id.toString(),
        posterUrl = null,
        releaseYear = null,
        productionStatus = ProductionStatus.RETURNING,
        totalEpisodes = 1,
        airedEpisodes = 1,
        favorite = false,
        addedAtEpochMs = 0L,
    )

    private val hundred = (1..100).map { MediaId.tmdbTv(it.toString()) }

    @Test
    fun a_100_title_library_refresh_never_exceeds_the_shared_concurrency() = runTest {
        val source = CountingSource()
        val repository = Repository(hundred.map(::entry))

        RefreshCollectionSnapshotsUseCase(repository, source)()

        assertEquals(100, source.fetches)
        assertEquals(REFRESH_CONCURRENCY, source.peak)
        assertEquals(100, repository.upserts)
    }

    @Test
    fun the_background_worker_path_is_concurrent_and_bounded_too() = runTest {
        val source = CountingSource()
        val repository = Repository(hundred.map(::entry))

        RefreshAndFindNewEpisodesUseCase(
            repository,
            source,
            object : AppClock {
                override fun nowEpochMs(): Long = 0L
            },
        )()

        assertEquals(100, source.fetches)
        assertEquals(REFRESH_CONCURRENCY, source.peak, "one at a time before EPIC 27, and never more than the shared cap")
        assertEquals(100, repository.upserts)
    }

    @Test
    fun the_worker_path_is_faster_than_serial_in_virtual_time() = runTest {
        val source = CountingSource()
        val repository = Repository(hundred.map(::entry))

        val useCase = RefreshAndFindNewEpisodesUseCase(
            repository,
            source,
            object : AppClock {
                override fun nowEpochMs(): Long = 0L
            },
        )
        useCase()

        // 100 fetches x 50ms serially would be 5000ms; four at a time is 1250ms.
        assertTrue(currentTime <= 1_250, "took $currentTime ms of virtual time")
    }

    @Test
    fun mapBounded_keeps_the_order_of_the_receiver() = runTest {
        val result = (1..20).toList().mapBounded(limit = 3) { n ->
            delay((21 - n).toLong()) // later elements finish first
            n * 2
        }

        assertEquals((1..20).map { it * 2 }, result)
    }

    @Test
    fun mapBounded_rejects_a_non_positive_limit() = runTest {
        assertFailsWith<IllegalArgumentException> { listOf(1).mapBounded(limit = 0) { it } }
    }
}
