package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.common.widget.AppWidgets
import com.codingpit.muviss.core.common.widget.WidgetRefresher
import com.codingpit.muviss.core.model.WatchProgress
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Defects 6, 8 and 10: the states a merge must never leave the local database
 * in, because the rest of the app assumes they cannot exist.
 *
 * `collectionEntry` and `episodeProgress` merge independently, and `seen` and
 * `episodePlay` do too, so each of these is reachable from two devices doing
 * something ordinary and nothing else.
 */
class SyncMergeInvariantsTest {

    private var widgetRefreshes = 0

    @AfterTest
    fun tearDown() = AppWidgets.reset()

    private fun entryRow(mediaId: String, aired: Int, total: Int, updatedAt: Long) = JsonObject(
        mapOf(
            "media_id" to JsonPrimitive(mediaId),
            "media_type" to JsonPrimitive("TV"),
            "title" to JsonPrimitive("Show"),
            "production_status" to JsonPrimitive("RETURNING"),
            "total_episodes" to JsonPrimitive(total),
            "aired_episodes" to JsonPrimitive(aired),
            "added_at_epoch_ms" to JsonPrimitive(1L),
            "updated_at_epoch_ms" to JsonPrimitive(updatedAt),
        ),
    )

    private fun progressRow(mediaId: String, episode: Int, seen: Boolean, updatedAt: Long) = JsonObject(
        mapOf(
            "episode_id" to JsonPrimitive("$mediaId/1/$episode"),
            "media_id" to JsonPrimitive(mediaId),
            "season_number" to JsonPrimitive(1),
            "episode_number" to JsonPrimitive(episode),
            "seen" to JsonPrimitive(seen),
            "updated_at_epoch_ms" to JsonPrimitive(updatedAt),
        ),
    )

    private fun playRow(mediaId: String, episode: Int, watchedAt: Long, updatedAt: Long, deleted: Boolean = false) = JsonObject(
        mapOf(
            "id" to JsonPrimitive("$mediaId/1/$episode@$watchedAt"),
            "episode_id" to JsonPrimitive("$mediaId/1/$episode"),
            "media_id" to JsonPrimitive(mediaId),
            "watched_at_epoch_ms" to JsonPrimitive(watchedAt),
            "updated_at_epoch_ms" to JsonPrimitive(updatedAt),
            "deleted" to JsonPrimitive(deleted),
        ),
    )

    // --- defect 6 -------------------------------------------------------------

    @Test
    fun a_pull_never_leaves_more_episodes_seen_than_aired() = runTest {
        // Device A ticked eight episodes when eight had aired. This device's
        // snapshot of the same show still says five: it was taken before the
        // last three aired, and nothing has refreshed it since.
        val server = FakeSupabaseServer()
        server.signUp("alice")
        server.seed("collection_entry", "alice", entryRow("tmdb:tv:1", aired = 5, total = 10, updatedAt = 1_000L))
        (1..8).forEach { server.seed("episode_progress", "alice", progressRow("tmdb:tv:1", it, seen = true, updatedAt = 2_000L)) }
        val device = TestDevice(server)

        device.sync()

        val entry = device.entry("tmdb:tv:1")!!
        val seen = device.progress("tmdb:tv:1").count { it.seen }
        assertEquals(8, seen)
        assertTrue(entry.airedEpisodes >= seen, "aired=${entry.airedEpisodes} < seen=$seen, and WatchProgress throws on that")
        // The thing the Library screen actually does with these two numbers.
        WatchProgress(MediaType.TV, seen, entry.airedEpisodes.toInt(), entry.totalEpisodes.toInt(), ProductionStatus.RETURNING)
    }

    // --- defect 8 -------------------------------------------------------------

    @Test
    fun an_episode_unticked_on_another_device_has_no_live_play_left_here() = runTest {
        // Device A un-ticked episode 1 at t=2000, which sets seen = 0 and
        // tombstones the play it knew about. This device had meanwhile
        // recorded a second viewing that A never saw.
        val server = FakeSupabaseServer()
        server.signUp("alice")
        server.seed("episode_progress", "alice", progressRow("tmdb:tv:1", 1, seen = false, updatedAt = 2_000L))
        server.seed("episode_play", "alice", playRow("tmdb:tv:1", 1, watchedAt = 1_000L, updatedAt = 2_000L, deleted = true))
        val device = TestDevice(server)
        device.tick("tmdb:tv:1", episode = 1, seen = true, updatedAt = 1_500L)
        device.play("tmdb:tv:1", episode = 1, watchedAt = 1_500L)

        device.sync()

        assertEquals(false, device.progress("tmdb:tv:1").single().seen)
        assertEquals(emptyList(), device.livePlays("tmdb:tv:1"), "seen = 0 with a live play makes recordPlaysForUnseen skip the episode forever")
    }

    @Test
    fun a_seen_episode_pulled_without_any_play_gets_one() = runTest {
        val server = FakeSupabaseServer()
        server.signUp("alice")
        server.seed("episode_progress", "alice", progressRow("tmdb:tv:1", 1, seen = true, updatedAt = 3_000L))
        val device = TestDevice(server)

        device.sync()

        val plays = device.livePlays("tmdb:tv:1")
        assertEquals(1, plays.size, "a tick with no viewing behind it breaks the invariant ADR 0011 rests on")
        assertEquals(3_000L, plays.single().watchedAtEpochMs, "the synthetic play sits at the progress timestamp")
    }

    // --- defect 10 ------------------------------------------------------------

    @Test
    fun a_pull_that_applies_rows_refreshes_the_home_screen_widgets() = runTest {
        val server = FakeSupabaseServer()
        server.signUp("alice")
        server.seed("collection_entry", "alice", entryRow("tmdb:tv:1", aired = 1, total = 1, updatedAt = 1_000L))
        AppWidgets.install(WidgetRefresher { widgetRefreshes++ })
        val device = TestDevice(server)

        device.sync()

        assertTrue(widgetRefreshes > 0, "a widget went on showing what it showed before the pull")
    }

    // --- issue #101 -------------------------------------------------------

    @Test
    fun a_pull_that_applies_rows_refreshes_just_the_titles_it_touched() = runTest {
        val server = FakeSupabaseServer()
        server.signUp("alice")
        server.seed("collection_entry", "alice", entryRow("tmdb:tv:1", aired = 1, total = 1, updatedAt = 1_000L))
        var refreshed: Set<String>? = null
        val device = TestDevice(server, titleRefresher = TitleRefresher { mediaIds -> refreshed = mediaIds })

        device.sync()

        assertEquals(setOf("tmdb:tv:1"), refreshed, "a pull that touched exactly one title must not ask to refresh the whole library")
    }

    @Test
    fun a_pull_with_nothing_to_apply_never_calls_the_title_refresher() = runTest {
        val server = FakeSupabaseServer()
        server.signUp("alice")
        var calls = 0
        val device = TestDevice(server, titleRefresher = TitleRefresher { calls++ })

        device.sync()

        assertEquals(0, calls, "an empty pull touched no titles, so there is nothing to refresh")
    }
}
