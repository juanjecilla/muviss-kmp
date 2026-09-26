package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Test double for [CollectionRepository]: [setRating] just records what it was called with. */
private class RecordingCollectionRepository : CollectionRepository {
    val setRatingCalls = mutableListOf<Pair<MediaId, Int?>>()

    override fun observeAll(): Flow<List<CollectionEntry>> = flowOf(emptyList())
    override fun observeEntry(mediaId: MediaId): Flow<CollectionEntry?> = error("not used")
    override suspend fun upsertSnapshot(details: MediaDetails) = error("not used")
    override suspend fun refreshSnapshot(details: MediaDetails) = error("not used")
    override suspend fun remove(mediaId: MediaId) = error("not used")
    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = error("not used")
    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = error("not used")
    override suspend fun setRating(mediaId: MediaId, rating: Int?) {
        setRatingCalls += mediaId to rating
    }

    override suspend fun setNote(mediaId: MediaId, note: String?) = error("not used")

    override suspend fun setRevisitWillingness(mediaId: MediaId, willing: Boolean?) = error("not used")

    override suspend fun setCoWatchPinned(mediaId: MediaId, pinned: Boolean) = error("not used")
}

/**
 * [SetRatingUseCase] is the only place the 1-10 range is enforced — the
 * repository/database column accept any integer — so it is the one
 * collection use case with actual logic worth testing in isolation (the
 * plain toggles are exercised indirectly via the ViewModel and repository
 * tests instead).
 */
class SetRatingUseCaseTest {

    private val mediaId = MediaId.tmdbMovie("603")

    @Test
    fun accepts_ratings_within_1_to_10() = runTest {
        val repository = RecordingCollectionRepository()
        val useCase = SetRatingUseCase(repository)

        useCase(mediaId, 1)
        useCase(mediaId, 10)

        assertEquals(listOf<Pair<MediaId, Int?>>(mediaId to 1, mediaId to 10), repository.setRatingCalls)
    }

    @Test
    fun null_clears_the_rating_without_validation() = runTest {
        val repository = RecordingCollectionRepository()
        val useCase = SetRatingUseCase(repository)

        useCase(mediaId, null)

        assertEquals(listOf<Pair<MediaId, Int?>>(mediaId to null), repository.setRatingCalls)
    }

    @Test
    fun rejects_a_rating_below_1() = runTest {
        val useCase = SetRatingUseCase(RecordingCollectionRepository())

        val result = runCatching { useCase(mediaId, 0) }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun rejects_a_rating_above_10() = runTest {
        val useCase = SetRatingUseCase(RecordingCollectionRepository())

        val result = runCatching { useCase(mediaId, 11) }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
