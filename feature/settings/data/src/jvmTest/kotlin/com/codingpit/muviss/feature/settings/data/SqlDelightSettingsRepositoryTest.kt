@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.data

import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.notifications.NotificationPermissionRequester
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.GenreCatalog
import com.codingpit.muviss.feature.settings.domain.MutedGenresCodec
import com.codingpit.muviss.feature.settings.domain.SetNotificationsEnabledUseCase
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class ImmediateDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

class SqlDelightSettingsRepositoryTest {

    private lateinit var database: MuvissDatabase
    private lateinit var repository: SqlDelightSettingsRepository

    @BeforeTest
    fun setUp() {
        database = inMemoryDatabase()
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
            // EPIC 47: a brand-new row is not opted in; Onboarding, the nudge or Settings ask.
            assertFalse(settings.notificationsEnabled)
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
    fun device_backup_defaults_to_off_and_persists() = runTest {
        repository.observeSettings().test {
            assertEquals(false, awaitItem().deviceBackupEnabled)
            cancelAndIgnoreRemainingEvents()
        }

        repository.setDeviceBackupEnabled(true)
        DefaultSettingsApi(repository, SetNotificationsEnabledUseCase(repository, NotificationPermissionRequester.Granted), MutedGenresSession(), NoGenres)
            .observeDeviceBackupEnabled().test {
                assertEquals(true, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
    }

    @Test
    fun setCrashReportsEnabled_persists_and_reaches_the_api() = runTest {
        repository.setCrashReportsEnabled(false)

        DefaultSettingsApi(repository, SetNotificationsEnabledUseCase(repository, NotificationPermissionRequester.Granted), MutedGenresSession(), NoGenres).observeCrashReportsEnabled().test {
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

/** A catalogue with nothing in it: these tests are about what the api does with settings. */
private object NoGenres : GenreCatalog {
    override suspend fun genres(type: MediaType) = Result.success(emptyList<Genre>())
    override suspend fun examples(type: MediaType, genreId: String, limit: Int) = Result.success(emptyList<MediaSummary>())
}

class MutedGenresSettingsTest {
    private val database = inMemoryDatabase()
    private val repository = SqlDelightSettingsRepository(
        database.appSettingsQueries,
        ExportQueries(database),
        ImmediateDispatchers(UnconfinedTestDispatcher()),
        FakeClock(1_000L),
        AppVersion("1.2.3", 42L),
    )
    private val talk = GenreKey(MediaType.TV, "10767")

    @Test
    fun a_fresh_install_mutes_talk_news_and_reality_exactly_as_the_codec_defaults_say() = runTest {
        // The column DEFAULT in AppSettings.sq is a literal; this is what keeps it equal to MutedGenresCodec.DEFAULTS.
        val settings = repository.observeSettings().first()

        assertEquals(MutedGenresCodec.DEFAULTS, settings.mutedGenres)
        assertFalse(settings.mutedGenresInSearch)
    }

    @Test
    fun unmuting_removes_only_that_genre() = runTest {
        repository.setGenreMuted(talk, "Talk", muted = false)

        assertEquals(setOf("10763", "10764"), repository.observeSettings().first().mutedGenres.map { it.key.id }.toSet())
    }

    @Test
    fun muting_a_movie_genre_is_stored_next_to_the_tv_ones() = runTest {
        repository.setGenreMuted(GenreKey(MediaType.MOVIE, "99"), "Documentary", muted = true)

        assertTrue(GenreKey(MediaType.MOVIE, "99") in repository.observeSettings().first().mutedGenres.map { it.key })
    }

    @Test
    fun muting_twice_keeps_one_entry() = runTest {
        repository.setGenreMuted(talk, "Talk", muted = true)

        assertEquals(1, repository.observeSettings().first().mutedGenres.count { it.key == talk })
    }

    @Test
    fun unmuting_everything_stays_empty_and_does_not_fall_back_to_the_defaults() = runTest {
        MutedGenresCodec.DEFAULTS.forEach { repository.setGenreMuted(it.key, it.name, muted = false) }

        assertEquals(emptyList(), repository.observeSettings().first().mutedGenres)
    }

    @Test
    fun the_search_switch_is_stored() = runTest {
        repository.setMutedGenresInSearch(true)

        assertTrue(repository.observeSettings().first().mutedGenresInSearch)
    }

    @Test
    fun the_api_lifts_muting_while_the_session_chip_shows_muted_and_puts_it_back() = runTest {
        val session = MutedGenresSession()
        val api = DefaultSettingsApi(repository, SetNotificationsEnabledUseCase(repository, NotificationPermissionRequester.Granted), session, NoGenres)

        val before = api.observeMuting().first()
        session.set(true)
        val during = api.observeMuting().first()

        assertEquals(setOf("10767", "10763", "10764"), before.effective.forType(MediaType.TV))
        assertTrue(before.hasMuted)
        assertEquals(emptySet(), during.effective.forType(MediaType.TV))
        assertTrue(during.hasMuted) // the chip stays visible so it can be turned back off
    }

    @Test
    fun search_is_unmuted_unless_the_user_opted_in() = runTest {
        val api = DefaultSettingsApi(repository, SetNotificationsEnabledUseCase(repository, NotificationPermissionRequester.Granted), MutedGenresSession(), NoGenres)

        assertEquals(emptySet(), api.observeMuting().first().forSearch.forType(MediaType.TV))
        repository.setMutedGenresInSearch(true)
        assertEquals(setOf("10767", "10763", "10764"), api.observeMuting().first().forSearch.forType(MediaType.TV))
    }
}
