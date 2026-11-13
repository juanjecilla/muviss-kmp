package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.feature.settings.domain.MutedGenreEntry
import com.codingpit.muviss.feature.settings.domain.MutedGenresCodec
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Muted Genres store (ADR 0036, #644): the conversion from the legacy string from each state a device can be in,
 * the first explicit change, and the export that derives the legacy string from the effective set.
 */
class MutedGenresStoreTest {

    private val dispatchers = object : AppDispatchers {
        override val default = UnconfinedTestDispatcher()
        override val io = default
    }

    private val talk = MutedGenreEntry(GenreKey(MediaType.TV, "10767"), "Talk")
    private val news = MutedGenreEntry(GenreKey(MediaType.TV, "10763"), "News")
    private val reality = MutedGenreEntry(GenreKey(MediaType.TV, "10764"), "Reality")
    private val horror = MutedGenreEntry(GenreKey(MediaType.MOVIE, "27"), "Horror")

    private suspend fun database(): MuvissDatabase = inMemoryDatabase().also { it.appSettingsQueries.ensureRow() }

    private suspend fun MuvissDatabase.setLegacy(value: String) = appSettingsQueries.updateMutedGenres(value)

    private fun repository(database: MuvissDatabase) = SqlDelightSettingsRepository(
        database.appSettingsQueries,
        ExportQueries(database),
        dispatchers,
        FakeClock(1_000L),
        AppVersion("1", 1L),
        database,
    )

    @Test
    fun `a device that never chose keeps the code defaults and gets no marker`() = runTest {
        val database = database()

        MutedGenresStore(database).convertLegacy(MutedGenresCodec.encode(MutedGenresCodec.DEFAULTS))

        assertNull(database.mutedGenreChoiceQueries.select().executeAsOneOrNull())
        assertTrue(database.mutedGenreQueries.selectAll().executeAsList().isEmpty())
        assertEquals(MutedGenresCodec.DEFAULTS, MutedGenresStore(database).effective())
    }

    @Test
    fun `a device that muted nothing converts to a marker with a tombstone for every default`() = runTest {
        val database = database()
        database.setLegacy("")

        MutedGenresStore(database).convertLegacy("")

        assertTrue(database.mutedGenreChoiceQueries.select().executeAsOne().isDirty)
        assertEquals(listOf("10763", "10764", "10767"), database.mutedGenreQueries.selectAll().executeAsList().map { it.genreId.toString() }.sorted())
        assertTrue(database.mutedGenreQueries.selectAll().executeAsList().all { it.deleted && it.updatedAtEpochMs == 0L && it.isDirty })
        assertEquals(emptyList(), MutedGenresStore(database).effective())
    }

    @Test
    fun `a custom list converts to live rows for the non-defaults and tombstones for the defaults it leaves out`() = runTest {
        val database = database()
        val custom = "movie:27:Horror;tv:10767:Talk"

        MutedGenresStore(database).convertLegacy(custom)

        assertEquals(listOf(talk, horror), MutedGenresStore(database).effective())
        val rows = database.mutedGenreQueries.selectAll().executeAsList().associateBy { it.genreId }
        assertEquals(false, rows.getValue(27L).deleted)
        assertNull(rows[10767L], "Talk is a default the list keeps, so it needs no row: the marker's snapshot mutes it")
        assertEquals(true, rows.getValue(10763L).deleted, "News is a default the string leaves out, so it is unmuted")
        assertEquals(true, rows.getValue(10764L).deleted, "Reality is a default the string leaves out, so it is unmuted")
    }

    @Test
    fun `a string equal to the defaults gets no marker, so the device keeps following future defaults`() = runTest {
        val database = database()
        val reordered = "tv:10764:Reality;tv:10767:Talk;tv:10763:News"

        MutedGenresStore(database).convertLegacy(reordered)

        assertNull(database.mutedGenreChoiceQueries.select().executeAsOneOrNull())
    }

    @Test
    fun `the conversion runs once and the legacy string is left at its default after it`() = runTest {
        val database = database()
        val store = MutedGenresStore(database)
        store.convertLegacy("movie:27:Horror")

        // A later "Replace with <account>'s library" clears the marker. The string must not bring the old list back.
        database.mutedGenreChoiceQueries.deleteAll()
        database.mutedGenreQueries.deleteAll()
        store.convertLegacy(database.appSettingsQueries.selectSettings().executeAsOne().mutedGenres)

        assertNull(database.mutedGenreChoiceQueries.select().executeAsOneOrNull())
        assertEquals(MutedGenresCodec.DEFAULTS, store.effective())
    }

    @Test
    fun `the first explicit change writes the marker and one decision, never the defaults`() = runTest {
        val database = database()
        val store = MutedGenresStore(database)

        store.choose(talk.key, "Talk", muted = false, nowEpochMs = 5_000L)

        assertEquals(MutedGenresCodec.encode(MutedGenresCodec.DEFAULTS), database.mutedGenreChoiceQueries.select().executeAsOne().defaultsAtChoice)
        val rows = database.mutedGenreQueries.selectAll().executeAsList()
        assertEquals(1, rows.size, "only the genre that changed is written")
        assertEquals(true, rows.single().deleted)
        assertEquals(5_000L, rows.single().updatedAtEpochMs)
        assertEquals(listOf(news, reality), store.effective())
    }

    @Test
    fun `a later change writes only its own decision and keeps the marker it has`() = runTest {
        val database = database()
        val store = MutedGenresStore(database)
        store.choose(talk.key, "Talk", muted = false, nowEpochMs = 5_000L)

        store.choose(horror.key, "Horror", muted = true, nowEpochMs = 6_000L)

        assertEquals(2, database.mutedGenreQueries.selectAll().executeAsList().size)
        assertEquals(5_000L, database.mutedGenreChoiceQueries.select().executeAsOne().updatedAtEpochMs)
        assertEquals(setOf(news, reality, horror), store.effective().toSet())
    }

    @Test
    fun `the export derives the legacy string from the effective set, converting a device that has not yet`() = runTest {
        val database = database()
        database.setLegacy("movie:27:Horror")

        val export = repository(database).exportData()

        val settings = Json.parseToJsonElement(export).jsonObject.getValue("settings").jsonObject
        assertEquals("movie:27:Horror", settings.getValue("mutedGenres").jsonPrimitive.content)
    }

    @Test
    fun `the export keeps tombstones so a backup carries an unmute`() = runTest {
        val database = database()
        MutedGenresStore(database).choose(talk.key, "Talk", muted = false, nowEpochMs = 5_000L)

        val export = repository(database).exportData()

        val decisions = Json.parseToJsonElement(export).jsonObject.getValue("mutedGenres").jsonArray
        assertTrue(decisions.any { it.jsonObject.getValue("deleted").jsonPrimitive.boolean }, "the unmute is carried, as a tombstone")
        assertEquals(1, decisions.size)
    }
}
