package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #1074: a removal on one device reaches a device that still holds the live row only if the backup carries the
 * tombstone. Each table gets the same round trip as `TasteProfileBackupTest`: the source removes the row, the
 * export keeps the tombstone, and a restore onto a device with the older live row deletes it.
 */
class TombstoneBackupTest {

    private val dispatchers = object : AppDispatchers {
        override val default = UnconfinedTestDispatcher()
        override val io = default
    }

    private fun exporter(database: MuvissDatabase) = SqlDelightSettingsRepository(
        database.appSettingsQueries,
        ExportQueries(database),
        dispatchers,
        FakeClock(9_000L),
        AppVersion("1.2.3", 42L),
    )

    private fun restorer(database: MuvissDatabase) = SqlDelightBackupRestorer(database, dispatchers)

    private suspend fun export(database: MuvissDatabase): MuvissDataExport = Json.decodeFromString(exporter(database).exportData())

    @Test
    fun `a removed title is in the export as a tombstone and deletes the live row on restore`() = runTest {
        val source = inMemoryDatabase()
        seedEntry(source, updatedAt = 1_000L)
        source.collectionEntryQueries.softDelete(now = 2_000L, mediaId = MATRIX)
        val backup = exporter(source).exportData()
        assertTrue(Json.decodeFromString<MuvissDataExport>(backup).collection.single().deleted)

        val other = inMemoryDatabase()
        seedEntry(other, updatedAt = 1_000L)
        restorer(other).restore(backup)

        assertTrue(other.collectionEntryQueries.selectById(MATRIX).awaitAsOneOrNull()!!.deleted)
        assertTrue(other.collectionEntryQueries.selectAll().awaitAsList().isEmpty())
    }

    @Test
    fun `a removed viewing is in the export as a tombstone and deletes the live play on restore`() = runTest {
        val source = inMemoryDatabase()
        seedPlay(source, updatedAt = 1_000L)
        source.episodePlayQueries.deleteById(updatedAtEpochMs = 2_000L, id = PLAY_ID)
        val backup = exporter(source).exportData()
        assertTrue(export(source).plays.single().deleted)

        val other = inMemoryDatabase()
        seedPlay(other, updatedAt = 1_000L)
        restorer(other).restore(backup)

        assertTrue(other.episodePlayQueries.selectById(PLAY_ID).awaitAsOneOrNull()!!.deleted)
        assertTrue(other.episodePlayQueries.selectAll().awaitAsList().isEmpty())
    }

    @Test
    fun `a removed snooze is in the export as a tombstone and deletes the live snooze on restore`() = runTest {
        val source = inMemoryDatabase()
        seedSnooze(source, updatedAt = 1_000L)
        source.triageSnoozeQueries.softDelete(now = 2_000L, mediaId = MATRIX)
        val backup = exporter(source).exportData()
        assertTrue(export(source).snoozes.single().deleted)

        val other = inMemoryDatabase()
        seedSnooze(other, updatedAt = 1_000L)
        restorer(other).restore(backup)

        assertTrue(other.triageSnoozeQueries.selectById(MATRIX).awaitAsOneOrNull()!!.deleted)
        assertTrue(other.triageSnoozeQueries.selectAll().awaitAsList().isEmpty())
    }

    @Test
    fun `a removed triage decision is in the export as a tombstone and deletes the live decision on restore`() = runTest {
        val source = inMemoryDatabase()
        seedDecision(source, updatedAt = 1_000L)
        source.triageDecisionQueries.softDelete(now = 2_000L, mediaId = MATRIX)
        val backup = exporter(source).exportData()
        assertTrue(export(source).triage.single().deleted)

        val other = inMemoryDatabase()
        seedDecision(other, updatedAt = 1_000L)
        restorer(other).restore(backup)

        assertTrue(other.triageDecisionQueries.selectById(MATRIX).awaitAsOneOrNull()!!.deleted)
        assertTrue(other.triageDecisionQueries.selectAll().awaitAsList().isEmpty())
    }

    @Test
    fun `a deleted list is in the export as a tombstone and deletes the live list on restore`() = runTest {
        val source = inMemoryDatabase()
        source.mediaListQueries.upsertList(LIST_ID, "Rainy day", 1_000L, 1_000L, false, false)
        source.mediaListQueries.softDeleteList(now = 2_000L, id = LIST_ID)
        val backup = exporter(source).exportData()
        assertTrue(export(source).lists.single().deleted)

        val other = inMemoryDatabase()
        other.mediaListQueries.upsertList(LIST_ID, "Rainy day", 1_000L, 1_000L, false, false)
        restorer(other).restore(backup)

        assertTrue(other.mediaListQueries.selectListById(LIST_ID).awaitAsOneOrNull()!!.deleted)
    }

    @Test
    fun `a removed list entry is in the export as a tombstone and deletes the live entry on restore`() = runTest {
        val source = inMemoryDatabase()
        source.mediaListQueries.upsertEntry(LIST_ID, MATRIX, 1_000L, 1_000L, false, false)
        source.mediaListQueries.removeEntry(now = 2_000L, listId = LIST_ID, mediaId = MATRIX)
        val backup = exporter(source).exportData()
        assertTrue(export(source).listEntries.single().deleted)

        val other = inMemoryDatabase()
        other.mediaListQueries.upsertEntry(LIST_ID, MATRIX, 1_000L, 1_000L, false, false)
        restorer(other).restore(backup)

        assertTrue(other.mediaListQueries.selectEntry(LIST_ID, MATRIX).awaitAsOneOrNull()!!.deleted)
    }

    @Test
    fun `the summary counts live titles and lists, not tombstones`() = runTest {
        val source = inMemoryDatabase()
        seedEntry(source, updatedAt = 1_000L)
        seedEntry(source, mediaId = OTHER, updatedAt = 2_000L, deleted = true)
        source.mediaListQueries.upsertList(LIST_ID, "Rainy day", 1_000L, 1_000L, false, false)
        source.mediaListQueries.upsertList("gone", "Old", 1_000L, 2_000L, false, true)

        val summary = restorer(inMemoryDatabase()).summarize(exporter(source).exportData())

        assertEquals(1, summary.titleCount)
        assertEquals(1, summary.listCount)
    }

    @Test
    fun `a restored tombstone is written dirty so the next sync carries the removal`() = runTest {
        val source = inMemoryDatabase()
        seedSnooze(source, updatedAt = 2_000L, deleted = true)
        val backup = exporter(source).exportData()

        val other = inMemoryDatabase()
        seedSnooze(other, updatedAt = 1_000L)
        restorer(other).restore(backup)

        val row = other.triageSnoozeQueries.selectById(MATRIX).awaitAsOneOrNull()!!
        assertTrue(row.deleted)
        assertTrue(row.isDirty)
    }

    @Test
    fun `a newer local row is not removed by an older backup tombstone`() = runTest {
        val source = inMemoryDatabase()
        seedEntry(source, updatedAt = 1_000L, deleted = true)
        val backup = exporter(source).exportData()

        val other = inMemoryDatabase()
        seedEntry(other, updatedAt = 5_000L)
        restorer(other).restore(backup)

        assertFalse(other.collectionEntryQueries.selectById(MATRIX).awaitAsOneOrNull()!!.deleted)
    }

    @Test
    fun `a viewing removed at or after its tick stays removed when the seen-progress backfill runs`() = runTest {
        // The backfill writes a play at the tick's stamp with `OR REPLACE`. A removal stamped at or after that tick
        // is the winner, so the backfill must not bring the viewing back.
        listOf(TICK_AT, TICK_AT + 1_000L).forEach { removedAt ->
            val source = inMemoryDatabase()
            seedProgress(source, updatedAt = TICK_AT)
            seedPlay(source, updatedAt = TICK_AT)
            source.episodePlayQueries.deleteById(updatedAtEpochMs = removedAt, id = PLAY_ID)
            val backup = exporter(source).exportData()

            val other = inMemoryDatabase()
            seedProgress(other, updatedAt = TICK_AT)
            restorer(other).restore(backup)

            assertTrue(other.episodePlayQueries.selectById(PLAY_ID).awaitAsOneOrNull()!!.deleted, "removed at $removedAt")
            assertTrue(other.episodePlayQueries.selectAll().awaitAsList().isEmpty(), "removed at $removedAt")
        }
    }

    @Test
    fun `a legacy backup with seen progress and no play history still backfills the viewing`() = runTest {
        val source = inMemoryDatabase()
        seedProgress(source, updatedAt = TICK_AT)
        val backup = exporter(source).exportData()
        assertTrue(export(source).plays.isEmpty())

        val other = inMemoryDatabase()
        seedProgress(other, updatedAt = TICK_AT)
        restorer(other).restore(backup)

        assertFalse(other.episodePlayQueries.selectById(PLAY_ID).awaitAsOneOrNull()!!.deleted)
    }

    private suspend fun seedProgress(database: MuvissDatabase, updatedAt: Long) {
        database.episodeProgressQueries.upsert(EPISODE, SHOW, 1L, 1L, true, updatedAt, false)
    }

    private suspend fun seedEntry(
        database: MuvissDatabase,
        mediaId: String = MATRIX,
        updatedAt: Long,
        deleted: Boolean = false,
    ) {
        database.collectionEntryQueries.upsert(
            mediaId = mediaId,
            mediaType = "MOVIE",
            title = mediaId,
            posterUrl = null,
            releaseYear = 1999L,
            productionStatus = "RELEASED",
            totalEpisodes = 1L,
            airedEpisodes = 1L,
            favorite = false,
            genres = "Action",
            runtimeMinutes = 136L,
            addedAtEpochMs = 1_000L,
            updatedAtEpochMs = updatedAt,
            isDirty = false,
            deleted = deleted,
            notificationsMuted = false,
            rating = null,
            note = null,
            revisitWillingness = null,
            coWatchPinned = false,
        )
    }

    private suspend fun seedPlay(database: MuvissDatabase, updatedAt: Long, deleted: Boolean = false) {
        database.episodePlayQueries.upsert(PLAY_ID, EPISODE, MATRIX, 1_000L, updatedAt, false, deleted)
    }

    private suspend fun seedSnooze(database: MuvissDatabase, updatedAt: Long, deleted: Boolean = false) {
        database.triageSnoozeQueries.upsert(
            MATRIX, "MOVIE", "The Matrix", 1999L, null, null, 1_000L, 20_100L, updatedAt, false, deleted,
        )
    }

    private suspend fun seedDecision(database: MuvissDatabase, updatedAt: Long, deleted: Boolean = false) {
        database.triageDecisionQueries.upsert(
            MATRIX, "MOVIE", "LATER", "The Matrix", null, 1_000L, true, updatedAt, false, deleted, false, null, "",
        )
    }

    private companion object {
        const val MATRIX = "tmdb:movie:603"
        const val OTHER = "tmdb:movie:550"
        const val LIST_ID = "list-1"
        const val SHOW = "tmdb:tv:1399"
        const val EPISODE = "tmdb:tv:1399/1/1"
        const val TICK_AT = 1_000L
        const val PLAY_ID = "$EPISODE@$TICK_AT"
    }
}
