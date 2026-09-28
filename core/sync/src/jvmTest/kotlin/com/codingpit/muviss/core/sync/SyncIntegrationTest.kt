@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.core.sync

import app.cash.turbine.test
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two devices, each with its own real database and the app's real repositories,
 * and one [FakeSupabaseServer] between them. What is asserted is what a person
 * would see: the same library on both, and none of the states the audit found.
 *
 * Deliberately not a restatement of the unit suites: those pin one defect at a
 * time with hand-written rows. These drive the write paths the app actually
 * has, so a change to how a repository stamps or flags a row shows up here.
 */
class SyncIntegrationTest {

    private val movie = MediaId.tmdbMovie("603")
    private val second = MediaId.tmdbMovie("604")

    private fun newSetup(maxRows: Int = 1000): Triple<FakeSupabaseServer, AppDevice, AppDevice> {
        val server = FakeSupabaseServer(maxRows = maxRows)
        return Triple(server, AppDevice(server), AppDevice(server))
    }

    /** Everything a device holds for the ids a scenario touches, in the shape the backend sends, so two devices (or a device and the server) can be compared. */
    private fun AppDevice.snapshot(mediaIds: List<MediaId>, episodes: List<String> = emptyList(), lists: List<String> = emptyList()): SyncChangeSet {
        val db = device.database
        return SyncChangeSet(
            collectionEntries = mediaIds.mapNotNull { db.collectionEntryQueries.selectById(it.toString()).executeAsOneOrNull()?.toChange() },
            episodeProgress = episodes.mapNotNull { db.episodeProgressQueries.selectByEpisodeId(it).executeAsOneOrNull()?.toChange() },
            mediaLists = lists.mapNotNull { db.mediaListQueries.selectListById(it).executeAsOneOrNull()?.toChange() },
            triageDecisions = mediaIds.mapNotNull { db.triageDecisionQueries.selectById(it.toString()).executeAsOneOrNull()?.toChange() },
            episodePlays = db.episodePlayQueries.selectAll().executeAsList().map { it.toChange() }.sortedBy { it.id },
        )
    }

    private fun AppDevice.livePlayCount(mediaId: MediaId): Int = device.database.episodePlayQueries.selectAll().executeAsList().count { it.mediaId == mediaId.toString() }

    // --- convergence ------------------------------------------------------------

    @Test
    fun two_devices_converge_over_every_table_including_nulls_and_tombstones() = runTest {
        val (_, a, b) = newSetup()
        a.at(1_000)
        a.collection.upsertSnapshot(showDetails(aired = 4))
        a.collection.upsertSnapshot(MediaDetails(MediaSummary(movie, "The Matrix", year = 1999)))
        a.collection.setRating(movie, 9)
        a.collection.setNote(movie, "loved it")
        a.collection.setFavorite(movie, true)
        a.progress.setSeenBulk(episodeIds(3), true)
        a.device.database.mediaListQueries.insertList("list-1", "Weekend", 1_000L, 1_000L, true, false)
        a.device.database.mediaListQueries.upsertEntry("list-1", movie.toString(), 1_000L, 1_000L, true, false)
        a.device.database.triageDecisionQueries.upsert(second.toString(), "movie", "SKIP", "Reloaded", null, 1_000L, true, 1_000L, true, false)
        assertTrue(a.sync() is SyncOutcome.Success)
        assertTrue(b.sync() is SyncOutcome.Success)

        // Then the destructive half: clear a rating and a note, un-tick, remove, delete.
        a.at(5_000)
        a.collection.setRating(movie, null)
        a.collection.setNote(movie, null)
        a.progress.setSeen(episodeIds(3).first(), false)
        a.collection.remove(SHOW)
        a.device.database.mediaListQueries.softDeleteList(5_000L, "list-1")
        a.device.database.triageDecisionQueries.softDelete(5_000L, second.toString())
        a.sync()
        b.sync()

        val ids = listOf(movie, SHOW, second)
        val episodes = episodeIds(3).map { it.toString() }
        assertEquals(a.snapshot(ids, episodes, listOf("list-1")), b.snapshot(ids, episodes, listOf("list-1")))
        val onB = b.device.entry(movie.toString())!!
        assertNull(onB.rating, "the cleared rating arrived as a clear, not as the old value")
        assertNull(onB.note)
        assertTrue(b.device.entry(SHOW.toString())!!.deleted)
        assertTrue(b.device.database.mediaListQueries.selectListById("list-1").executeAsOne().deleted)
        assertEquals(false, b.device.progress(SHOW.toString()).first { it.episodeNumber == 1L }.seen)
    }

    // --- scale ------------------------------------------------------------------

    @Test
    fun a_2500_episode_library_survives_a_server_that_cuts_every_response_at_1000_rows() = runTest {
        val (_, a, b) = newSetup(maxRows = 1000)
        a.at(1_000)
        a.collection.upsertSnapshot(showDetails(aired = 2_500))
        a.progress.setSeenBulk(episodeIds(2_500), true)

        assertTrue(a.sync() is SyncOutcome.Success)
        val outcome = b.sync()

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals(2_500, b.device.allProgress().size)
        assertEquals(2_500, b.livePlayCount(SHOW))
        b.collection.observeEntry(SHOW).test {
            val entry = awaitItem()!!
            assertEquals(2_500, entry.seenEpisodes)
            assertEquals(WatchStatus.WATCHED, entry.status, "every aired episode is seen and the show is still returning")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- races and clocks -------------------------------------------------------

    @Test
    fun an_offline_edit_reaches_a_device_whose_cursor_has_already_moved_past_it() = runTest {
        val (_, a, b) = newSetup()
        a.at(1_000)
        a.collection.upsertSnapshot(MediaDetails(MediaSummary(movie, "The Matrix")))
        a.collection.upsertSnapshot(MediaDetails(MediaSummary(second, "Reloaded")))
        a.sync()
        b.sync()

        b.at(2_000)
        b.collection.setFavorite(movie, true)
        a.at(9_000)
        a.collection.setFavorite(second, true)
        a.sync()
        b.sync()
        a.sync()

        assertTrue(a.device.entry(movie.toString())!!.favorite)
        assertTrue(b.device.entry(second.toString())!!.favorite)
    }

    @Test
    fun a_snapshot_refresh_on_a_stale_device_does_not_beat_another_devices_edit_or_undo_its_removal() = runTest {
        val (_, a, b) = newSetup()
        a.at(1_000)
        a.collection.upsertSnapshot(MediaDetails(MediaSummary(movie, "The Matrix")))
        a.collection.upsertSnapshot(MediaDetails(MediaSummary(second, "Reloaded")))
        a.sync()
        b.sync()

        // B rates one title and removes the other. A, which has not synced since, opens the Library.
        b.at(5_000)
        b.collection.setRating(movie, 10)
        b.collection.remove(second)
        a.at(9_000)
        a.collection.refreshSnapshot(MediaDetails(MediaSummary(movie, "The Matrix (refreshed)")))
        a.collection.refreshSnapshot(MediaDetails(MediaSummary(second, "Reloaded (refreshed)")))
        assertTrue(a.device.database.collectionEntryQueries.selectDirty().executeAsList().isEmpty(), "looking at the Library must not queue anything to sync")
        a.sync()
        b.sync()
        a.sync()

        assertEquals(10L, a.device.entry(movie.toString())!!.rating, "the refresh did not outrank B's rating")
        assertTrue(a.device.entry(second.toString())!!.deleted, "and did not undo B's removal")
        assertEquals("The Matrix (refreshed)", a.device.entry(movie.toString())!!.title, "while A's own snapshot stayed as refreshed as it was")
    }

    // --- invariants the app throws on -------------------------------------------

    @Test
    fun ticks_pulled_ahead_of_this_devices_snapshot_do_not_break_status_derivation() = runTest {
        val (_, a, b) = newSetup()
        a.at(1_000)
        a.collection.upsertSnapshot(showDetails(aired = 8))
        a.progress.setSeenBulk(episodeIds(8), true)
        a.sync()
        // B saved the show earlier, when only five had aired, and has not refreshed since.
        b.at(500)
        b.collection.upsertSnapshot(showDetails(aired = 5))
        b.device.database.collectionEntryQueries.markAllDirty()

        b.sync()

        b.collection.observeEntry(SHOW).test {
            val entry = awaitItem()!!
            assertEquals(8, entry.seenEpisodes)
            assertTrue(entry.airedEpisodes >= entry.seenEpisodes)
            entry.status // the derivation that throws when seen > aired
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun a_tick_on_one_device_and_an_untick_on_another_leaves_no_orphan_play() = runTest {
        val (_, a, b) = newSetup()
        val episode = episodeIds(1).single()
        a.at(1_000)
        a.collection.upsertSnapshot(showDetails(aired = 3))
        a.progress.setSeen(episode, true)
        a.sync()
        b.sync()

        // B un-ticks at 2000. A, offline, watches it again at 1500's successor...
        b.at(3_000)
        b.progress.setSeen(episode, false)
        a.at(2_000)
        a.progress.recordPlay(episode)
        b.sync()
        a.sync()
        b.sync()

        assertEquals(false, a.device.progress(SHOW.toString()).single { it.episodeNumber == 1L }.seen)
        assertEquals(0, a.livePlayCount(SHOW), "an un-ticked episode keeps no live viewing")
        assertEquals(0, b.livePlayCount(SHOW))
        assertEquals(listOf(episode), a.progress.recordPlaysForUnseen(listOf(episode)), "and can be marked watched again, which a leftover play would have blocked")
    }

    // --- duplicate plays (#87/#97) -----------------------------------------------

    @Test
    fun two_devices_ticking_the_same_episode_minutes_apart_merge_into_one_viewing() = runTest {
        val (_, a, b) = newSetup()
        val episode = episodeIds(1).single()
        a.at(1_000)
        a.collection.upsertSnapshot(showDetails(aired = 1))
        assertTrue(a.sync() is SyncOutcome.Success)
        b.sync()

        // Neither device has synced since: this is the exact shape #87/#97
        // describe, a first watch ticked on two phones before either goes
        // back online, 90 seconds apart.
        a.at(10_000)
        a.progress.setSeen(episode, true)
        b.at(10_000 + 90_000)
        b.progress.setSeen(episode, true)

        a.sync()
        b.sync() // pulls A's play, merges locally: B's own duplicate loses to A's earlier one.
        a.sync() // pulls B's (now-tombstoned-on-B-but-not-yet-pushed) play, merges the same way independently.

        assertEquals(1, a.livePlayCount(SHOW), "the two near-simultaneous ticks merge into one viewing")
        assertEquals(1, b.livePlayCount(SHOW))
        a.progressApi.observeRewatchCounts(0).test {
            assertEquals(emptyMap(), awaitItem(), "a merged duplicate must not be counted as a rewatch")
            cancelAndIgnoreRemainingEvents()
        }

        // One more round each way so both tombstones reach the server too —
        // full convergence, not just each device's own local view.
        b.sync()
        a.sync()
        assertEquals(a.snapshot(listOf(SHOW), listOf(episode.toString())), b.snapshot(listOf(SHOW), listOf(episode.toString())), "both devices and the server agree on exactly one surviving play")
    }

    @Test
    fun two_devices_watching_the_same_episode_weeks_apart_both_count_as_real_viewings() = runTest {
        val (_, a, b) = newSetup()
        val episode = episodeIds(1).single()
        a.at(1_000)
        a.collection.upsertSnapshot(showDetails(aired = 1))
        assertTrue(a.sync() is SyncOutcome.Success)
        b.sync()

        a.at(10_000)
        a.progress.setSeen(episode, true)
        a.sync()
        b.sync()

        // B watches the same episode again three weeks later — a genuine
        // rewatch, not a duplicate, even though it is the same episode B
        // just pulled as already seen.
        b.at(10_000 + 21L * 24 * 60 * 60 * 1000)
        b.progress.recordPlay(episode)
        b.sync()
        a.sync()

        assertEquals(2, a.livePlayCount(SHOW), "outside the 5-minute window both viewings survive")
        assertEquals(2, b.livePlayCount(SHOW))
        a.progressApi.observeRewatchCounts(0).test {
            assertEquals(mapOf(SHOW to 1), awaitItem(), "and the second one is a genuine rewatch")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- interruptions ----------------------------------------------------------

    @Test
    fun an_interrupted_push_resumes_without_resending_what_already_landed() = runTest {
        val (server, a, b) = newSetup()
        a.at(1_000)
        a.collection.upsertSnapshot(showDetails(aired = 1_200))
        a.progress.setSeenBulk(episodeIds(1_200), true)
        var progressPosts = 0
        server.failWith(503, times = 1) { it.isUpsert && it.table == "episode_progress" && ++progressPosts == 2 }

        val failed = a.sync()
        assertTrue(failed is SyncOutcome.Failed)
        server.clearRecordedRequests()
        val resumed = a.sync()
        b.sync()

        assertTrue(resumed is SyncOutcome.Success)
        val resent = server.requestsTo("episode_progress", "POST").sumOf { it.rows.size }
        assertEquals(700, resent, "the 500 rows that landed before the failure were not sent again")
        assertEquals(1_200, b.device.allProgress().size)
    }

    @Test
    fun an_interrupted_pull_moves_nothing_and_the_retry_completes() = runTest {
        val (server, a, b) = newSetup(maxRows = 300)
        a.at(1_000)
        a.collection.upsertSnapshot(showDetails(aired = 1_000))
        a.progress.setSeenBulk(episodeIds(1_000), true)
        a.sync()
        var progressGets = 0
        server.failWith(503, times = 1) { it.isSelect && it.table == "episode_progress" && ++progressGets == 3 }

        val failed = b.sync()
        assertTrue(failed is SyncOutcome.Failed)
        assertEquals(emptyList(), b.device.database.syncCursorQueries.selectAll().executeAsList(), "no cursor moved")

        val retried = b.sync()

        assertTrue(retried is SyncOutcome.Success)
        assertEquals(1_000, b.device.allProgress().size)
    }

    // --- accounts ---------------------------------------------------------------

    @Test
    fun switching_accounts_discards_the_first_library_and_never_sends_it_to_the_second() = runTest {
        val (server, a, _) = newSetup()
        a.at(1_000)
        a.collection.upsertSnapshot(MediaDetails(MediaSummary(movie, "Alice's movie")))
        a.sync()
        a.collection.upsertSnapshot(MediaDetails(MediaSummary(second, "Alice's unsent movie")))
        val bobsPhone = AppDevice(server, userId = "bob")
        bobsPhone.at(1_000)
        bobsPhone.collection.upsertSnapshot(MediaDetails(MediaSummary(MediaId.tmdbMovie("1"), "Bob's movie")))
        bobsPhone.sync()

        a.device.signInAs("bob")
        val stopped = a.sync()
        val resolved = a.device.engine.resolveAccountChange(AccountChangeResolution.DiscardLocalData)

        assertEquals(SyncOutcome.AccountChanged("alice", "bob"), stopped)
        assertTrue(resolved is SyncOutcome.Success)
        assertEquals(listOf("tmdb:movie:1"), server.rows("collection_entry", "bob").map { it.getValue("media_id").toString().trim('"') }, "bob's account holds only bob's library")
        assertEquals(listOf("tmdb:movie:1"), a.device.database.collectionEntryQueries.selectAll().executeAsList().map { it.mediaId })
    }

    @Test
    fun the_first_sync_of_an_install_that_predates_the_plays_sends_them() = runTest {
        val (server, a, _) = newSetup()
        // A library from before plays synced: ticks and viewings, all marked clean because they were "pushed" long ago.
        a.device.tick(SHOW.toString(), episode = 1, updatedAt = 1_000L, isDirty = false)
        a.device.play(SHOW.toString(), episode = 1, watchedAt = 1_000L, isDirty = false)
        a.device.play(SHOW.toString(), episode = 1, watchedAt = 2_000L, isDirty = false)

        a.sync()

        assertEquals(2, server.rows("episode_play", "alice").size)
    }
}
