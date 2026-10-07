@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.settings.domain.AppTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ImmediateDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

private class FakeClock(private var millis: Long) : AppClock {
    override fun nowEpochMs(): Long = millis
}

class SqlDelightSettingsRepositoryTest {

    private lateinit var database: MuvissDatabase
    private lateinit var repository: SqlDelightSettingsRepository

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        database = MuvissDatabase(driver)
        repository = SqlDelightSettingsRepository(
            database.appSettingsQueries,
            ExportQueries(database),
            ImmediateDispatchers(UnconfinedTestDispatcher()),
            FakeClock(1_000L),
            AppVersion("1.2.3", 42L),
        )
    }

    @Test
    fun observeSettings_defaults_to_system_theme_and_default_locale() = runTest {
        repository.observeSettings().test {
            val settings = awaitItem()
            assertEquals(AppTheme.SYSTEM, settings.theme)
            // A fresh row seeds language as the "System default" sentinel, not
            // a hardcoded "en-US" (issue #136) — SupportedLocales.resolveLanguage
            // is what turns this into a concrete TMDB language.
            assertEquals("", settings.language)
            assertEquals("US", settings.region)
            assertTrue(settings.notificationsEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setTheme_persists_and_is_observed() = runTest {
        repository.observeSettings().test {
            assertEquals(AppTheme.SYSTEM, awaitItem().theme)

            repository.setTheme(AppTheme.DARK)
            assertEquals(AppTheme.DARK, awaitItem().theme)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setLanguage_and_setRegion_persist_independently() = runTest {
        repository.setLanguage("es-ES")
        repository.setRegion("ES")

        repository.observeSettings().test {
            val settings = awaitItem()
            assertEquals("es-ES", settings.language)
            assertEquals("ES", settings.region)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setNotificationsEnabled_persists() = runTest {
        repository.setNotificationsEnabled(false)

        repository.observeSettings().test {
            assertEquals(false, awaitItem().notificationsEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun crash_reports_default_to_on() = runTest {
        repository.observeSettings().test {
            assertEquals(true, awaitItem().crashReportsEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setCrashReportsEnabled_persists_and_reaches_the_api() = runTest {
        repository.setCrashReportsEnabled(false)

        DefaultSettingsApi(repository).observeCrashReportsEnabled().test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setCrashReportsEnabled(true)
        repository.observeSettings().test {
            assertEquals(true, awaitItem().crashReportsEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun exportData_produces_valid_json_containing_library_and_progress() = runTest {
        database.collectionEntryQueries.upsert(
            mediaId = "tmdb:movie:603",
            mediaType = "MOVIE",
            title = "The Matrix",
            posterUrl = null,
            releaseYear = 1999L,
            productionStatus = "RELEASED",
            totalEpisodes = 1L,
            airedEpisodes = 1L,
            favorite = true,
            genres = "Action,Sci-Fi",
            runtimeMinutes = 136L,
            addedAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
            isDirty = true,
            deleted = false,
            notificationsMuted = false,
            rating = null,
            note = null,
            revisitWillingness = null,
            coWatchPinned = false,
        )
        database.episodeProgressQueries.upsert(
            episodeId = "tmdb:tv:1399:s1:e1",
            mediaId = "tmdb:tv:1399",
            seasonNumber = 1L,
            episodeNumber = 1L,
            seen = true,
            updatedAtEpochMs = 2_000L,
            isDirty = true,
        )

        val json = repository.exportData()
        val parsed = Json.parseToJsonElement(json).jsonObject

        val collection = parsed["collection"]!!.jsonArray
        assertEquals(1, collection.size)
        assertEquals("The Matrix", collection[0].jsonObject["title"]!!.jsonPrimitive.content)

        val progress = parsed["progress"]!!.jsonArray
        assertEquals(1, progress.size)
        assertEquals("tmdb:tv:1399", progress[0].jsonObject["mediaId"]!!.jsonPrimitive.content)
    }

    @Test
    fun exportData_excludes_soft_deleted_collection_entries() = runTest {
        database.collectionEntryQueries.upsert(
            mediaId = "tmdb:movie:603",
            mediaType = "MOVIE",
            title = "The Matrix",
            posterUrl = null,
            releaseYear = 1999L,
            productionStatus = "RELEASED",
            totalEpisodes = 1L,
            airedEpisodes = 1L,
            favorite = false,
            genres = "",
            runtimeMinutes = null,
            addedAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
            isDirty = true,
            deleted = true,
            notificationsMuted = false,
            rating = null,
            note = null,
            revisitWillingness = null,
            coWatchPinned = false,
        )

        val json = repository.exportData()
        val collection = Json.parseToJsonElement(json).jsonObject["collection"]!!.jsonArray
        assertTrue(collection.isEmpty())
    }

    @Test
    fun the_export_carries_rewatch_history() = runTest {
        listOf(1_000L, 2_000L).forEach { watchedAt ->
            database.episodePlayQueries.upsert(
                id = "tmdb:tv:1399/1/1@$watchedAt",
                episodeId = "tmdb:tv:1399/1/1",
                mediaId = "tmdb:tv:1399",
                watchedAtEpochMs = watchedAt,
                updatedAtEpochMs = watchedAt,
                isDirty = false,
                deleted = false,
            )
        }

        val json = repository.exportData()

        // A person's backup has to include how often they watched things;
        // leaving plays out would silently drop that on reinstall (ADR 0011).
        assertTrue(json.contains("\"plays\""))
        assertTrue(json.contains("\"watchedAtEpochMs\": 2000"))
    }

    @Test
    fun `a v2 export is versioned and carries what v1 dropped`() = runTest {
        database.collectionEntryQueries.upsert(
            mediaId = "tmdb:tv:1399",
            mediaType = "TV",
            title = "Game of Thrones",
            posterUrl = null,
            releaseYear = 2011L,
            productionStatus = "ENDED",
            totalEpisodes = 73L,
            airedEpisodes = 73L,
            favorite = false,
            genres = "Drama,Fantasy",
            runtimeMinutes = 60L,
            addedAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
            isDirty = false,
            deleted = false,
            notificationsMuted = true,
            rating = 8L,
            note = "Skip season 8",
            revisitWillingness = null,
            coWatchPinned = false,
        )
        database.mediaListQueries.upsertList(id = "list-1", name = "Rainy day", createdAtEpochMs = 1_000L, updatedAtEpochMs = 1_000L, isDirty = false, deleted = false)
        database.mediaListQueries.upsertEntry(listId = "list-1", mediaId = "tmdb:tv:1399", addedAtEpochMs = 1_500L, isDirty = false, deleted = false, updatedAtEpochMs = 1_500L)
        database.mediaListQueries.upsertList(id = "list-gone", name = "Deleted", createdAtEpochMs = 1_000L, updatedAtEpochMs = 1_000L, isDirty = false, deleted = true)
        database.triageSnoozeQueries.upsert(
            mediaId = "tmdb:movie:1",
            mediaType = "MOVIE",
            title = "Later Maybe",
            year = 2020L,
            posterUrl = null,
            overview = null,
            snoozedAtEpochMs = 1_000L,
            dueAtEpochDay = 20_100L,
            updatedAtEpochMs = 1_000L,
            isDirty = false,
            deleted = false,
        )
        database.profileQueries.ensureRow()
        database.profileQueries.updateDisplayName("Juanje")

        val export = Json.decodeFromString<MuvissDataExport>(repository.exportData())

        assertEquals(MuvissDataExport.CURRENT_FORMAT_VERSION, export.formatVersion)
        assertEquals("1.2.3", export.appVersion)
        val entry = export.collection.single()
        assertEquals(8, entry.rating)
        assertEquals("Skip season 8", entry.note)
        assertEquals("Drama,Fantasy", entry.genres)
        assertEquals(60, entry.runtimeMinutes)
        assertTrue(entry.notificationsMuted)
        assertEquals(listOf("Rainy day"), export.lists.map { it.name })
        assertEquals(listOf("list-1" to "tmdb:tv:1399"), export.listEntries.map { it.listId to it.mediaId })
        assertEquals(listOf(20_100L), export.snoozes.map { it.dueAtEpochDay })
        assertEquals("Juanje", export.profile?.displayName)
    }

    @Test
    fun `a v1 export with no version still parses, as version 1`() {
        val v1 = """{"exportedAtEpochMs":1,"collection":[{"mediaId":"tmdb:movie:603","mediaType":"MOVIE","title":"The Matrix",""" +
            """"posterUrl":null,"releaseYear":1999,"productionStatus":"RELEASED","totalEpisodes":1,"airedEpisodes":1,""" +
            """"favorite":true,"addedAtEpochMs":1,"updatedAtEpochMs":1}],"progress":[]}"""

        val export = Json.decodeFromString<MuvissDataExport>(v1)

        assertEquals(1, export.formatVersion)
        assertEquals(null, export.collection.single().rating)
        assertTrue(export.lists.isEmpty())
    }
}
