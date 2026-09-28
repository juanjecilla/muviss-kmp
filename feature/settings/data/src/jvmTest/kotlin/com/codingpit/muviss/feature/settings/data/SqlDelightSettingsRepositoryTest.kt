@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
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
            ExportQueries(
                collection = database.collectionEntryQueries,
                progress = database.episodeProgressQueries,
                plays = database.episodePlayQueries,
                triage = database.triageDecisionQueries,
            ),
            ImmediateDispatchers(UnconfinedTestDispatcher()),
            FakeClock(1_000L),
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
}
