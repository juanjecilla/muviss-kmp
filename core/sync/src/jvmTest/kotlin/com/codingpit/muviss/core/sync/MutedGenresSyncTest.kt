package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.testing.FakeSupabaseServer
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync coverage for Muted Genres (ADR 0036, #644): the choice marker and the decisions over its defaults. Two real
 * [TestDevice]s share one [FakeSupabaseServer]. The defaults are a string in the codec's format, as the marker stores.
 */
class MutedGenresSyncTest {

    private val server = FakeSupabaseServer()

    private fun device() = TestDevice(server, userId = ACCOUNT)

    /** A device's first explicit choice, as the store writes it: the marker, then the one decision. */
    private suspend fun TestDevice.choose(mediaType: String, genreId: Long, name: String, muted: Boolean, at: Long) {
        if (database.mutedGenreChoiceQueries.select().executeAsOneOrNull() == null) {
            database.mutedGenreChoiceQueries.upsert(defaultsAtChoice = DEFAULTS, updatedAtEpochMs = at, isDirty = true)
        }
        database.mutedGenreQueries.upsert(mediaType = mediaType, genreId = genreId, name = name, deleted = !muted, isDirty = true, updatedAtEpochMs = at)
    }

    /** A legacy conversion's marker, stamped 0 and dirty (ADR 0036). */
    private suspend fun TestDevice.convertedMarker() {
        database.mutedGenreChoiceQueries.upsert(defaultsAtChoice = DEFAULTS, updatedAtEpochMs = 0L, isDirty = true)
    }

    private fun TestDevice.decision(genreId: Long) = database.mutedGenreQueries.selectOne("tv", genreId).executeAsOneOrNull()

    private fun accountDecisions(): List<JsonObject> = server.rows("muted_genre", ACCOUNT)

    private fun accountDecision(mediaType: String, genreId: Long): JsonObject? = server.row("muted_genre", ACCOUNT, "$mediaType|$genreId")

    private fun accountMarker(): JsonObject? = server.rows("muted_genre_choice", ACCOUNT).singleOrNull()

    private fun JsonObject.text(column: String): String = getValue(column).jsonPrimitive.content

    @Test
    fun `an_unmute_on_one_device_is_not_undone_when_a_fresh_device_mutes_another_genre`() = runTest {
        // A unmutes Talk at t1, a default. A fresh B mutes Horror later. Under decisions B pushes its marker and
        // the Horror row, and nothing about Talk, so A's unmute survives both syncs.
        val a = device()
        a.database.mutedGenreChoiceQueries.upsert(defaultsAtChoice = DEFAULTS, updatedAtEpochMs = 1_000L, isDirty = true)
        a.database.mutedGenreQueries.upsert(mediaType = "tv", genreId = TALK, name = "Talk", deleted = true, isDirty = true, updatedAtEpochMs = 1_000L)
        a.sync()

        val b = device()
        b.choose(mediaType = "movie", genreId = HORROR, name = "Horror", muted = true, at = 2_000L)
        b.sync()
        a.sync()

        assertTrue(a.decision(TALK)?.deleted == true, "A still has Talk unmuted")
        assertEquals("1000", accountDecision("tv", TALK)?.getValue("updated_at_epoch_ms")?.jsonPrimitive?.content)
        assertNull(accountDecision("tv", NEWS), "B pushed nothing about News")
        assertNull(accountDecision("tv", REALITY), "B pushed nothing about Reality")
        assertEquals("movie", accountDecision("movie", HORROR)?.text("media_type"))
    }

    @Test
    fun `a_device_that_never_chose_pushes_no_decisions_and_no_marker`() = runTest {
        val fresh = device()

        fresh.sync()

        assertTrue(accountDecisions().isEmpty())
        assertNull(accountMarker())
    }

    @Test
    fun `a_decision_made_on_both_devices_resolves_by_stamp`() = runTest {
        val a = device()
        a.choose(mediaType = "tv", genreId = TALK, name = "Talk", muted = false, at = 1_000L)
        a.sync()

        val b = device()
        b.choose(mediaType = "tv", genreId = TALK, name = "Talk", muted = true, at = 3_000L)
        b.sync()
        a.sync()

        assertFalse(a.decision(TALK)?.deleted ?: true, "the later mute wins on A too")
    }

    @Test
    fun `a_tombstone_propagates_so_an_unmute_reaches_the_other_device`() = runTest {
        val a = device()
        a.choose(mediaType = "tv", genreId = NEWS, name = "News", muted = false, at = 1_000L)
        a.sync()

        val b = device()
        b.sync()

        assertTrue(b.decision(NEWS)?.deleted == true)
        assertFalse(b.decision(NEWS)?.isDirty ?: true, "a pulled decision is the account's, not a change")
    }

    @Test
    fun `a_converted_marker_is_replaced_by_the_account_marker_never_pushed_over_it`() = runTest {
        val a = device()
        a.choose(mediaType = "tv", genreId = TALK, name = "Talk", muted = false, at = 1_000L)
        a.sync()

        val b = device()
        b.convertedMarker()
        b.sync()

        assertEquals(1_000L, b.database.mutedGenreChoiceQueries.select().executeAsOne().updatedAtEpochMs)
        assertEquals(1_000L, accountMarker()?.getValue("updated_at_epoch_ms")?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `a_converted_marker_is_pushed_when_the_account_has_none`() = runTest {
        val b = device()
        b.convertedMarker()

        b.sync()

        assertEquals(DEFAULTS, accountMarker()?.text("defaults_at_choice"))
        assertFalse(b.database.mutedGenreChoiceQueries.select().executeAsOne().isDirty)
    }

    @Test
    fun `resync_and_discard_treat_the_marker_and_decisions_as_the_account_s`() = runTest {
        val database = inMemoryDatabase()
        database.mutedGenreChoiceQueries.upsert(defaultsAtChoice = DEFAULTS, updatedAtEpochMs = 1_000L, isDirty = false)
        database.mutedGenreQueries.upsert(mediaType = "tv", genreId = TALK, name = "Talk", deleted = true, isDirty = false, updatedAtEpochMs = 1_000L)

        LocalChangeLog(database).markAllDirty()

        assertTrue(database.mutedGenreChoiceQueries.select().executeAsOne().isDirty)
        assertTrue(database.mutedGenreQueries.selectDirty().executeAsList().isNotEmpty())

        LocalChangeLog(database).deleteAllUserData()

        assertNull(database.mutedGenreChoiceQueries.select().executeAsOneOrNull())
        assertTrue(database.mutedGenreQueries.selectAll().executeAsList().isEmpty())
    }

    private companion object {
        const val ACCOUNT = "alice"
        const val DEFAULTS = "tv:10767:Talk;tv:10763:News;tv:10764:Reality"
        const val TALK = 10767L
        const val NEWS = 10763L
        const val REALITY = 10764L
        const val HORROR = 27L
    }
}
