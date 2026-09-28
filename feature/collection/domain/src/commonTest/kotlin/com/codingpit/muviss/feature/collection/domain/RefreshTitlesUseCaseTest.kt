package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The bounded counterpart to [RefreshCollectionSnapshotsUseCaseTest] — issue #101. */
class RefreshTitlesUseCaseTest {

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

    private class RecordingSource : MediaSnapshotSource {
        val fetched = mutableListOf<MediaId>()
        override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> {
            fetched += mediaId
            return Result.success(MediaDetails(MediaSummary(mediaId, mediaId.toString())))
        }
    }

    @Test
    fun refreshes_only_the_requested_titles_not_the_whole_library() = runTest {
        val touched = MediaId.tmdbMovie("1")
        val untouched = MediaId.tmdbMovie("2")
        val repository = Repository(listOf(entry(touched), entry(untouched)))
        val source = RecordingSource()

        RefreshTitlesUseCase(repository, source)(setOf(touched))

        assertEquals(listOf(touched), source.fetched, "a title the pull never touched must not be re-fetched")
        assertEquals(listOf(touched), repository.upserted)
    }

    @Test
    fun an_id_not_in_the_library_is_silently_skipped() = runTest {
        val repository = Repository(emptyList())
        val source = RecordingSource()

        RefreshTitlesUseCase(repository, source)(setOf(MediaId.tmdbMovie("not-saved")))

        assertEquals(emptyList(), source.fetched)
    }

    @Test
    fun an_empty_set_never_touches_the_source() = runTest {
        val repository = Repository(listOf(entry(MediaId.tmdbMovie("1"))))
        val source = RecordingSource()

        RefreshTitlesUseCase(repository, source)(emptySet())

        assertEquals(emptyList(), source.fetched)
    }
}
