package com.codingpit.muviss.core.sync.supabase

import com.codingpit.muviss.core.sync.CollectionEntryChange
import com.codingpit.muviss.core.sync.EpisodeProgressChange
import com.codingpit.muviss.core.sync.FakeClock
import com.codingpit.muviss.core.sync.InMemorySessionStore
import com.codingpit.muviss.core.sync.SyncChangeSet
import com.codingpit.muviss.core.sync.SyncCursor
import com.codingpit.muviss.core.sync.SyncPage
import com.codingpit.muviss.core.sync.SyncTable
import com.codingpit.muviss.core.sync.TEST_BASE_URL
import com.codingpit.muviss.core.sync.productionLikeClient
import com.codingpit.muviss.core.sync.sessionFor
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real [SupabaseSyncBackend] against [FakeSupabaseServer], asserting the
 * bytes it puts on the wire and what it does with the answers — the layer the
 * in-memory fake could not see.
 */
class SupabaseWireContractTest {

    private val server = FakeSupabaseServer()
    private val backend = SupabaseSyncBackend(
        client = productionLikeClient(server.engine),
        baseUrl = TEST_BASE_URL,
        anonKey = "anon",
        sessionStore = InMemorySessionStore(sessionFor("alice").also { server.signUp("alice") }),
        clock = FakeClock(1_000L),
    )

    private fun entry(mediaId: String, rating: Int? = null, note: String? = null, updatedAt: Long = 1_000L) = CollectionEntryChange(
        mediaId = mediaId, mediaType = "MOVIE", title = "T", posterUrl = null, releaseYear = null, productionStatus = "RELEASED",
        totalEpisodes = 0, airedEpisodes = 0, favorite = false, genres = "", runtimeMinutes = null, addedAtEpochMs = 1L,
        updatedAtEpochMs = updatedAt, deleted = false, rating = rating, note = note,
    )

    private fun tick(episode: Int) = EpisodeProgressChange("tmdb:tv:1/1/$episode", "tmdb:tv:1", 1, episode, true, 1_000L + episode)

    private suspend fun pullAll(after: Map<SyncTable, SyncCursor> = emptyMap()): List<SyncPage> {
        val pages = mutableListOf<SyncPage>()
        backend.pull(after) { pages += it }.getOrThrow()
        return pages
    }

    @Test
    fun a_push_body_carries_null_columns_as_explicit_nulls() = runTest {
        backend.push(SyncChangeSet(collectionEntries = listOf(entry("tmdb:movie:1", rating = null, note = null)))).getOrThrow()

        val body = server.requestsTo("collection_entry", "POST").single().body
        listOf("rating", "note", "poster_url", "release_year", "runtime_minutes").forEach { column ->
            assertTrue("\"$column\":null" in body, "$column was left out of the body, so the server would keep its old value: $body")
        }
    }

    @Test
    fun a_push_uses_merge_duplicates_and_sends_no_server_owned_column() = runTest {
        backend.push(SyncChangeSet(collectionEntries = listOf(entry("tmdb:movie:1")))).getOrThrow()

        val request = server.requestsTo("collection_entry", "POST").single()
        assertTrue("resolution=merge-duplicates" in request.headers.getValue("Prefer"))
        assertFalse("server_seq" in request.body)
        assertFalse("user_id" in request.body)
    }

    @Test
    fun a_pull_asks_for_server_seq_order_a_page_limit_and_a_gt_filter() = runTest {
        pullAll()

        val request = server.requestsTo("collection_entry", "GET").first()
        assertEquals("server_seq.asc", request.query["order"])
        assertEquals("500", request.query["limit"])
        assertEquals("gt.0", request.query["server_seq"])
    }

    @Test
    fun a_pull_resumes_from_the_cursor_it_is_given() = runTest {
        backend.push(SyncChangeSet(collectionEntries = listOf(entry("tmdb:movie:1"), entry("tmdb:movie:2")))).getOrThrow()
        val firstPass = pullAll()
        val cursor = firstPass.last().cursor

        val second = pullAll(mapOf(SyncTable.COLLECTION_ENTRY to cursor))

        assertTrue(second.isEmpty(), "nothing is newer than the cursor")
        assertEquals("gt.${cursor.position}", server.requestsTo("collection_entry", "GET").last().query["server_seq"])
    }

    @Test
    fun the_page_loop_stops_on_an_empty_page_even_when_max_rows_cuts_every_response() = runTest {
        server.maxRows = 300
        backend.push(SyncChangeSet(episodeProgress = (1..1_000).map(::tick))).getOrThrow()

        val pages = pullAll().filter { it.table == SyncTable.EPISODE_PROGRESS }

        assertEquals(1_000, pages.sumOf { it.changes.episodeProgress.size })
        assertTrue(pages.all { it.changes.episodeProgress.size <= 300 }, "the server cut each response at its cap")
        // 4 pages of data, then the empty one that proves the table is drained.
        assertEquals(5, server.requestsTo("episode_progress", "GET").size)
    }

    @Test
    fun cursors_only_move_forward_and_never_repeat_a_row() = runTest {
        backend.push(SyncChangeSet(episodeProgress = (1..1_200).map(::tick))).getOrThrow()

        val pages = pullAll().filter { it.table == SyncTable.EPISODE_PROGRESS }

        assertEquals(pages.map { it.cursor.position }.sorted(), pages.map { it.cursor.position })
        assertEquals(1_200, pages.flatMap { it.changes.episodeProgress }.map { it.episodeId }.toSet().size)
    }

    @Test
    fun a_stale_push_does_not_show_up_as_a_change_in_the_feed() = runTest {
        backend.push(SyncChangeSet(collectionEntries = listOf(entry("tmdb:movie:1", updatedAt = 5_000L)))).getOrThrow()
        val cursor = pullAll().last().cursor

        backend.push(SyncChangeSet(collectionEntries = listOf(entry("tmdb:movie:1", updatedAt = 4_000L)))).getOrThrow()

        assertTrue(pullAll(mapOf(SyncTable.COLLECTION_ENTRY to cursor)).isEmpty(), "a discarded write keeps its old server_seq")
    }

    /** A backend whose `episode_progress` answers [body] and every other table answers empty, for shapes [FakeSupabaseServer] would never produce. */
    private fun backendAnswering(body: String) = SupabaseSyncBackend(
        client = productionLikeClient(
            MockEngine { request ->
                val answer = if (request.url.encodedPath.endsWith("/episode_progress")) body else "[]"
                respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
        baseUrl = TEST_BASE_URL,
        anonKey = "anon",
        sessionStore = InMemorySessionStore(sessionFor("alice")),
        clock = FakeClock(1_000L),
    )

    @Test
    fun a_server_that_ignores_order_is_an_error_not_a_silent_skip() = runTest {
        val misordering = backendAnswering(
            """[{"server_seq":5,"episode_id":"a","media_id":"m","season_number":1,"episode_number":1,"seen":true,"updated_at_epoch_ms":1},
                {"server_seq":2,"episode_id":"b","media_id":"m","season_number":1,"episode_number":2,"seen":true,"updated_at_epoch_ms":1}]""",
        )

        val result = misordering.pull(emptyMap()) { }

        assertTrue(result.isFailure, "a misordered page was accepted")
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("ascending"), "unexpected failure: ${result.exceptionOrNull()}")
    }

    @Test
    fun a_pull_from_a_project_without_the_migration_names_what_is_missing() = runTest {
        val result = backendAnswering("""[{"media_id":"m"}]""").pull(emptyMap()) { }

        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("server_seq"))
    }
}
