package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The refresh is the Library's pull-to-refresh action, so how *many* titles it
 * has in flight at once is the difference between a spinner that stops and one
 * that appears to hang: a serial walk over a 20-show library issues 20 x (1 +
 * one-per-season) requests back to back.
 */
class RefreshCollectionSnapshotsUseCaseTest {

    private fun entry(id: MediaId) = CollectionEntry(
        mediaId = id,
        title = id.toString(),
        posterUrl = null,
        releaseYear = null,
        productionStatus = ProductionStatus.RELEASED,
        totalEpisodes = 1,
        airedEpisodes = 1,
        favorite = false,
        addedAtEpochMs = 0L,
    )

    private class Repository(entries: List<CollectionEntry>) : CollectionRepository {
        private val flow = MutableStateFlow(entries)
        val upserted = mutableListOf<MediaId>()

        override fun observeAll(): Flow<List<CollectionEntry>> = flow
        override fun observeEntry(mediaId: MediaId): Flow<CollectionEntry?> = error("not used")

        // A refresh must not go through the add path: that one is a synced write (EPIC 39).
        override suspend fun upsertSnapshot(details: MediaDetails) = error("a refresh must use refreshSnapshot")

        override suspend fun refreshSnapshot(details: MediaDetails) {
            upserted += details.id
        }

        override suspend fun remove(mediaId: MediaId) = error("not used")
        override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = error("not used")
        override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = error("not used")
        override suspend fun setRating(mediaId: MediaId, rating: Int?) = error("not used")
        override suspend fun setNote(mediaId: MediaId, note: String?) = error("not used")

        override suspend fun setRevisitWillingness(mediaId: MediaId, willing: Boolean?) = error("not used")

        override suspend fun setCoWatchPinned(mediaId: MediaId, pinned: Boolean) = error("not used")
    }

    /** Records how many fetches were ever in flight together, and blocks until released. */
    private class GatedSource : MediaSnapshotSource {
        private val gate = CompletableDeferred<Unit>()
        private var inFlight = 0
        var peakInFlight = 0
            private set
        val started = mutableListOf<MediaId>()

        override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> {
            inFlight++
            peakInFlight = maxOf(peakInFlight, inFlight)
            started += mediaId
            gate.await()
            inFlight--
            return Result.success(MediaDetails(MediaSummary(mediaId, mediaId.toString())))
        }

        fun release() = gate.complete(Unit)
    }

    @Test
    fun refreshes_several_titles_concurrently_rather_than_one_at_a_time() = runTest {
        val ids = (1..8).map { MediaId.tmdbMovie(it.toString()) }
        val repository = Repository(ids.map(::entry))
        val source = GatedSource()
        val useCase = RefreshCollectionSnapshotsUseCase(repository, source, concurrency = 4)

        val job = async { useCase() }
        testScheduler.advanceUntilIdle()

        assertEquals(4, source.peakInFlight, "expected the refresh to run 4 titles at a time")

        source.release()
        job.await()
        assertEquals(ids.toSet(), repository.upserted.toSet())
    }

    @Test
    fun never_exceeds_the_configured_concurrency() = runTest {
        val ids = (1..12).map { MediaId.tmdbMovie(it.toString()) }
        val repository = Repository(ids.map(::entry))
        val source = GatedSource()
        val useCase = RefreshCollectionSnapshotsUseCase(repository, source, concurrency = 3)

        val job = async { useCase() }
        testScheduler.advanceUntilIdle()

        assertTrue(source.peakInFlight <= 3, "peak in flight was ${source.peakInFlight}")

        source.release()
        job.await()
    }

    @Test
    fun one_failing_title_does_not_stop_the_rest() = runTest {
        val ids = (1..3).map { MediaId.tmdbMovie(it.toString()) }
        val repository = Repository(ids.map(::entry))
        val failing = ids.first()
        val source = object : MediaSnapshotSource {
            override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> = if (mediaId == failing) {
                Result.failure(IllegalStateException("boom"))
            } else {
                Result.success(MediaDetails(MediaSummary(mediaId, mediaId.toString())))
            }
        }

        RefreshCollectionSnapshotsUseCase(repository, source)()

        assertEquals(ids.drop(1).toSet(), repository.upserted.toSet())
    }
}
