package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.feature.settings.domain.ImportFileError
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

    private fun newDatabase(): MuvissDatabase = inMemoryDatabase()

    private fun exporter(database: MuvissDatabase) = SqlDelightSettingsRepository(
        database.appSettingsQueries,
        ExportQueries(database),
        dispatchers,
        FakeClock(9_000L),
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

    // ---- display preferences (#392) -----------------------------------------------------------

    private suspend fun changePreferences(database: MuvissDatabase) = database.appSettingsQueries.run {
        ensureRow()
        updateTheme("DARK")
        updateLanguage("es-ES")
        updateRegion("ES")
        updateAnimationsEnabled(false)
        updateTriageDeckAnimations(false)
        updateTickAnimations(false)
        updateTriageControlScheme("TWO_WAY")
        updateTriageSnoozePeriod("ONE_MONTH")
        updateTriageSnoozePlacement("FIRST")
        updateMutedGenres("movie:27:Horror")
        updateMutedGenresInSearch(true)
        // Device-bound and one-time flags are not part of a backup.
        updateNotificationsEnabled(true)
        updateSyncAutomatically(true)
        updateCrashReportsEnabled(false)
        updateDiscoverIntroSeen(true)
    }

    @Test
    fun `a restore on a clean database brings the display preferences back`() = runTest {
        val source = newDatabase()
        changePreferences(source)
        val backup = exporter(source).exportData()

        val fresh = newDatabase()
        restorer(fresh).restore(backup)

        val row = fresh.appSettingsQueries.selectSettings().awaitAsOne()
        assertEquals("DARK", row.theme)
        assertEquals("es-ES", row.language)
        assertEquals("ES", row.region)
        assertEquals(false, row.animationsEnabled)
        assertEquals(false, row.triageDeckAnimations)
        assertEquals(false, row.tickAnimations)
        assertEquals("TWO_WAY", row.triageControlScheme)
        assertEquals("ONE_MONTH", row.triageSnoozePeriod)
        assertEquals("FIRST", row.triageSnoozePlacement)
        assertEquals("movie:27:Horror", row.mutedGenres)
        assertEquals(true, row.mutedGenresInSearch)
    }

    @Test
    fun `device-bound settings and one-time hints stay out of the backup and the restore`() = runTest {
        val source = newDatabase()
        changePreferences(source)
        val backup = exporter(source).exportData()

        val fresh = newDatabase()
        restorer(fresh).restore(backup)

        val row = fresh.appSettingsQueries.selectSettings().awaitAsOne()
        assertEquals(false, row.notificationsEnabled, "a fresh install is asked, never opted in")
        assertEquals(false, row.syncAutomatically)
        assertEquals(true, row.crashReportsEnabled)
        assertEquals(false, row.discoverIntroSeen)
    }

    @Test
    fun `a preference already chosen on this device is kept over the backup's`() = runTest {
        val source = newDatabase()
        changePreferences(source)
        val backup = exporter(source).exportData()

        val local = newDatabase()
        local.appSettingsQueries.ensureRow()
        local.appSettingsQueries.updateTheme("LIGHT")

        restorer(local).restore(backup)

        val row = local.appSettingsQueries.selectSettings().awaitAsOne()
        assertEquals("LIGHT", row.theme, "chosen here, so the backup does not replace it")
        assertEquals("es-ES", row.language, "still at its default, so the backup fills it")
    }

    @Test
    fun `restoring the same backup twice changes nothing the second time`() = runTest {
        val source = newDatabase()
        changePreferences(source)
        val backup = exporter(source).exportData()
        val fresh = newDatabase()

        restorer(fresh).restore(backup)
        val first = fresh.appSettingsQueries.selectSettings().awaitAsOne()
        restorer(fresh).restore(backup)

        assertEquals(first, fresh.appSettingsQueries.selectSettings().awaitAsOne())
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

        val e = assertFailsWith<ImportFileException> { restorer(newDatabase()).restore(future) }
        assertEquals(ImportFileError.NewerBackupVersion, e.kind)
    }

    @Test
    fun `a malformed backup is reported, not thrown raw`() = runTest {
        val e = assertFailsWith<ImportFileException> { restorer(newDatabase()).restore("""{"exportedAtEpochMs": "yesterday"}""") }
        assertEquals(ImportFileError.UnreadableBackup, e.kind)
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
