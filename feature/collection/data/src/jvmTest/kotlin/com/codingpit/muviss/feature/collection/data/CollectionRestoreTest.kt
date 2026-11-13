package com.codingpit.muviss.feature.collection.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Undoing a removal (#721) against real SQLite: the entry comes back with what the person said about it and the
 * counts it had, not rebuilt from the details on screen. A plain re-add would reset the user's fields.
 */
class CollectionRestoreTest {

    private lateinit var db: MuvissDatabase
    private lateinit var repository: SqlDelightCollectionRepository
    private val show = MediaId.tmdbTv("1399")
    private val seasons = listOf(
        Season(1, "S1", listOf(Episode(EpisodeId(show, 1, 1), 1, 1, "Pilot", airDateEpochDay = 100L))),
    )

    @BeforeTest
    fun setUp() {
        db = inMemoryDatabase()
        repository = SqlDelightCollectionRepository(db.collectionEntryQueries, RestoreTestDispatchers(UnconfinedTestDispatcher()), FakeClock(epochMsAtStartOfDay(150)))
    }

    @Test
    fun removing_then_restoring_keeps_the_favorite_rating_note_add_date_and_stored_counts() = runTest {
        repository.upsertSnapshot(MediaDetails(MediaSummary(show, "Show"), productionStatus = ProductionStatus.RETURNING, seasons = seasons))
        repository.setFavorite(show, true)
        repository.setRating(show, 8)
        repository.setNote(show, "keep this")
        val before = db.collectionEntryQueries.selectById(show.toString()).executeAsOne()

        repository.remove(show)
        assertTrue(db.collectionEntryQueries.selectById(show.toString()).executeAsOne().deleted)
        repository.restore(show)

        val after = db.collectionEntryQueries.selectById(show.toString()).executeAsOne()
        assertFalse(after.deleted)
        assertTrue(after.isDirty, "a restore is a user action, so it syncs")
        assertTrue(after.favorite)
        assertEquals(8L, after.rating)
        assertEquals("keep this", after.note)
        assertEquals(before.addedAtEpochMs, after.addedAtEpochMs)
        assertEquals(before.airedEpisodes, after.airedEpisodes)
        assertEquals(before.totalEpisodes, after.totalEpisodes)
    }

    @Test
    fun restoring_a_title_that_is_not_removed_changes_nothing() = runTest {
        repository.upsertSnapshot(MediaDetails(MediaSummary(show, "Show"), productionStatus = ProductionStatus.RETURNING, seasons = seasons))
        val before = db.collectionEntryQueries.selectById(show.toString()).executeAsOne()

        repository.restore(show)

        assertEquals(before, db.collectionEntryQueries.selectById(show.toString()).executeAsOne())
    }
}

private class RestoreTestDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}
