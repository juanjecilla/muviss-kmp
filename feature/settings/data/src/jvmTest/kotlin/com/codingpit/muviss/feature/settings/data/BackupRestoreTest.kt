package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.settings.domain.ImportFileException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** EPIC 29 (#72): a backup you can restore. */
class BackupRestoreTest {

    private val dispatchers = object : AppDispatchers {
        override val default = UnconfinedTestDispatcher()
        override val io = default
    }

    private fun newDatabase(): MuvissDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        return MuvissDatabase(driver)
    }

    private fun exporter(database: MuvissDatabase) = SqlDelightSettingsRepository(
        database.appSettingsQueries,
        ExportQueries(database),
        dispatchers,
        object : AppClock {
            override fun nowEpochMs(): Long = 9_000L
        },
        AppVersion("1.2.3", 42L),
    )

    private fun restorer(database: MuvissDatabase) = SqlDelightBackupRestorer(database, dispatchers)

    @Test
    fun `export, wipe, restore, export again gives the same backup`() = runTest {
        val source = newDatabase()
        seedEverything(source)
        val first = exporter(source).exportData()

        val fresh = newDatabase()
        restorer(fresh).restore(first)
        val second = exporter(fresh).exportData()

        assertEquals(Json.decodeFromString<MuvissDataExport>(first), Json.decodeFromString<MuvissDataExport>(second))
    }

    @Test
    fun `a v1 export still restores`() = runTest {
        val v1 = javaClass.getResource("/backups/v1-export.json")!!.readText()
        val database = newDatabase()

        val summary = restorer(database).summarize(v1)
        val result = restorer(database).restore(v1)

        assertEquals(1, summary.formatVersion)
        assertEquals(2, summary.titleCount)
        assertEquals(2, summary.episodeCount)
        assertEquals(0, result.kept)
        assertEquals(listOf("Game of Thrones", "The Matrix"), database.collectionEntryQueries.selectAll().awaitAsList().map { it.title }.sorted())
        assertEquals(2L, database.episodeProgressQueries.countSeenForMedia("tmdb:tv:1399").awaitAsOne())
        assertEquals(2, database.episodePlayQueries.selectAll().awaitAsList().size)
        assertEquals("SKIP", database.triageDecisionQueries.selectById("tmdb:movie:550").awaitAsOne().verdict)
    }

    @Test
    fun `an older backup never overwrites a newer local row`() = runTest {
        val database = newDatabase()
        val backup = exportOf { seedEntry(it, updatedAt = 1_000L, note = "from the backup") }
        seedEntry(database, updatedAt = 5_000L, note = "edited since")

        val result = restorer(database).restore(backup)

        assertEquals("edited since", database.collectionEntryQueries.selectById(MATRIX).awaitAsOne().note)
        assertEquals(1, result.kept)
    }

    @Test
    fun `a newer backup row replaces an older local one, and is left to sync`() = runTest {
        val database = newDatabase()
        val backup = exportOf { seedEntry(it, updatedAt = 5_000L, note = "from the backup") }
        seedEntry(database, updatedAt = 1_000L, note = "stale")

        restorer(database).restore(backup)

        val row = database.collectionEntryQueries.selectById(MATRIX).awaitAsOne()
        assertEquals("from the backup", row.note)
        assertTrue(row.isDirty, "a restored row is a change the server has not seen")
    }

    @Test
    fun `restoring the same file twice changes nothing the second time`() = runTest {
        val source = newDatabase()
        seedEverything(source)
        val backup = exporter(source).exportData()
        val database = newDatabase()

        restorer(database).restore(backup)
        val second = restorer(database).restore(backup)

        assertEquals(0, second.restored)
    }

    @Test
    fun `ticks that outnumber the restored snapshot's aired count raise it`() = runTest {
        // A local snapshot newer than the backup's, but older in what it knows has aired.
        val database = newDatabase()
        val backup = exportOf { db ->
            seedEntry(db, mediaId = SHOW, mediaType = "TV", updatedAt = 1_000L, aired = 2L)
            seedTick(db, "$SHOW/1/1", 1)
            seedTick(db, "$SHOW/1/2", 2)
        }
        seedEntry(database, mediaId = SHOW, mediaType = "TV", updatedAt = 5_000L, aired = 1L)

        restorer(database).restore(backup)

        assertEquals(2L, database.collectionEntryQueries.selectById(SHOW).awaitAsOne().airedEpisodes)
    }

    @Test
    fun `a backup from a newer version is refused, not half-read`() = runTest {
        val future = """{"formatVersion": 99, "exportedAtEpochMs": 1, "collection": [], "progress": []}"""

        assertFailsWith<ImportFileException> { restorer(newDatabase()).restore(future) }
    }

    @Test
    fun `a malformed backup is reported, not thrown raw`() = runTest {
        assertFailsWith<ImportFileException> { restorer(newDatabase()).restore("""{"exportedAtEpochMs": "yesterday"}""") }
    }

    @Test
    fun `the profile is restored only over an untouched one`() = runTest {
        val backup = exportOf { db ->
            db.profileQueries.ensureRow()
            db.profileQueries.updateDisplayName("From backup")
        }
        val untouched = newDatabase()
        val renamed = newDatabase().also {
            it.profileQueries.ensureRow()
            it.profileQueries.updateDisplayName("Chosen here")
        }

        restorer(untouched).restore(backup)
        restorer(renamed).restore(backup)

        assertEquals("From backup", untouched.profileQueries.selectProfile().awaitAsOne().displayName)
        assertEquals("Chosen here", renamed.profileQueries.selectProfile().awaitAsOne().displayName)
    }

    private suspend fun exportOf(seed: suspend (MuvissDatabase) -> Unit): String {
        val database = newDatabase()
        seed(database)
        return exporter(database).exportData()
    }

    private suspend fun seedEverything(database: MuvissDatabase) {
        seedEntry(database, updatedAt = 1_000L, note = "Watch with popcorn", rating = 9L)
        seedEntry(database, mediaId = SHOW, mediaType = "TV", updatedAt = 1_000L, aired = 2L)
        seedTick(database, "$SHOW/1/1", 1)
        seedTick(database, "$SHOW/1/2", 2)
        database.episodePlayQueries.upsert("$SHOW/1/1@1100", "$SHOW/1/1", SHOW, 1_100L, 1_100L, false, false)
        database.episodePlayQueries.upsert("$SHOW/1/2@1200", "$SHOW/1/2", SHOW, 1_200L, 1_200L, false, false)
        database.mediaListQueries.upsertList("list-1", "Rainy day", 1_000L, 1_000L, false, false)
        database.mediaListQueries.upsertEntry("list-1", MATRIX, 1_500L, 1_500L, false, false)
        database.triageDecisionQueries.upsert("tmdb:movie:550", "MOVIE", "SKIP", "Fight Club", null, 2_000L, true, 2_000L, false, false)
        database.triageSnoozeQueries.upsert("tmdb:movie:1", "MOVIE", "Later", 2020L, null, null, 3_000L, 20_100L, 3_000L, false, false)
        database.profileQueries.ensureRow()
        database.profileQueries.updateDisplayName("Juanje")
    }

    @Suppress("LongParameterList") // one row, named at each call site
    private suspend fun seedEntry(
        database: MuvissDatabase,
        mediaId: String = MATRIX,
        mediaType: String = "MOVIE",
        updatedAt: Long,
        note: String? = null,
        rating: Long? = null,
        aired: Long = 1L,
    ) {
        database.collectionEntryQueries.upsert(
            mediaId = mediaId,
            mediaType = mediaType,
            title = mediaId,
            posterUrl = null,
            releaseYear = 1999L,
            productionStatus = "RELEASED",
            totalEpisodes = aired,
            airedEpisodes = aired,
            favorite = false,
            genres = "Action",
            runtimeMinutes = 136L,
            addedAtEpochMs = 1_000L,
            updatedAtEpochMs = updatedAt,
            isDirty = false,
            deleted = false,
            notificationsMuted = false,
            rating = rating,
            note = note,
            revisitWillingness = null,
            coWatchPinned = false,
        )
    }

    private suspend fun seedTick(database: MuvissDatabase, episodeId: String, episode: Long) {
        database.episodeProgressQueries.upsert(episodeId, SHOW, 1L, episode, true, 1_000L + episode * 100, false)
    }

    private companion object {
        const val MATRIX = "tmdb:movie:603"
        const val SHOW = "tmdb:tv:1399"
    }
}
