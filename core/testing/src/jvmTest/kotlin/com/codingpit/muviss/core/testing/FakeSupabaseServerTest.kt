package com.codingpit.muviss.core.testing

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The fixture is only worth having if it is faithful, so each behaviour the
 * sync tests lean on is pinned here directly, over raw HTTP and with no sync
 * code involved. When one of these changes, `supabase/migrations` and
 * `supabase/tests` are what it has to agree with.
 */
class FakeSupabaseServerTest {

    private var serverNow = 1_000_000L
    private val server = FakeSupabaseServer(serverClock = { serverNow })
    private val client = HttpClient(server.engine)
    private val token = server.signUp("alice")

    private fun entry(mediaId: String, updatedAt: Long, vararg extra: Pair<String, Any?>): JsonObject = JsonObject(
        buildMap {
            put("media_id", JsonPrimitive(mediaId))
            put("media_type", JsonPrimitive("MOVIE"))
            put("title", JsonPrimitive("Title $mediaId"))
            put("production_status", JsonPrimitive("RELEASED"))
            put("added_at_epoch_ms", JsonPrimitive(1L))
            put("updated_at_epoch_ms", JsonPrimitive(updatedAt))
            extra.forEach { (k, v) ->
                put(
                    k,
                    when (v) {
                        null -> JsonNull
                        is Number -> JsonPrimitive(v)
                        is Boolean -> JsonPrimitive(v)
                        else -> JsonPrimitive(v.toString())
                    },
                )
            }
        },
    )

    private suspend fun upsert(table: String, rows: List<JsonObject>, bearer: String = token, merge: Boolean = true): HttpResponse = client.post("http://fake/rest/v1/$table") {
        header("apikey", "anon")
        header("Authorization", "Bearer $bearer")
        if (merge) header("Prefer", "resolution=merge-duplicates,return=minimal")
        setBody(io.ktor.http.content.TextContent(JsonArray(rows).toString(), io.ktor.http.ContentType.Application.Json))
    }

    private suspend fun select(table: String, query: String = "", bearer: String = token): List<JsonObject> {
        val response = client.get("http://fake/rest/v1/$table?select=*$query") {
            header("apikey", "anon")
            header("Authorization", "Bearer $bearer")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return (Json.parseToJsonElement(response.bodyAsText()) as JsonArray).map { it as JsonObject }
    }

    private fun JsonObject.seq(): Long = getValue("server_seq").jsonPrimitive.long

    @Test
    fun a_column_absent_from_every_object_in_the_body_is_left_as_stored() = runTest {
        upsert("collection_entry", listOf(entry("m1", 10, "rating" to 8)))

        // The client cleared the rating locally and sent no "rating" key at all.
        upsert("collection_entry", listOf(entry("m1", 20)))

        assertEquals(8L, server.row("collection_entry", "alice", "m1")!!.getValue("rating").jsonPrimitive.long)
    }

    @Test
    fun an_explicit_null_clears_the_column() = runTest {
        upsert("collection_entry", listOf(entry("m1", 10, "rating" to 8)))

        upsert("collection_entry", listOf(entry("m1", 20, "rating" to null)))

        assertEquals(JsonNull, server.row("collection_entry", "alice", "m1")!!.getValue("rating"))
    }

    @Test
    fun a_key_present_in_one_object_is_null_for_the_objects_that_lack_it() = runTest {
        upsert("collection_entry", listOf(entry("a", 10, "note" to "keep me"), entry("b", 10, "note" to "and me")))

        // "note" is in the union (object a carries it), so object b's missing key becomes NULL.
        upsert("collection_entry", listOf(entry("a", 20, "note" to "changed"), entry("b", 20)))

        assertEquals("changed", server.row("collection_entry", "alice", "a")!!.getValue("note").jsonPrimitive.content)
        assertEquals(JsonNull, server.row("collection_entry", "alice", "b")!!.getValue("note"))
    }

    @Test
    fun a_strictly_older_write_is_discarded_with_a_2xx_and_keeps_its_server_seq() = runTest {
        upsert("collection_entry", listOf(entry("m1", 50, "title" to "current")))
        val seqBefore = server.row("collection_entry", "alice", "m1")!!.seq()

        val response = upsert("collection_entry", listOf(entry("m1", 40, "title" to "stale")))

        assertTrue(response.status.value in 200..299)
        val stored = server.row("collection_entry", "alice", "m1")!!
        assertEquals("current", stored.getValue("title").jsonPrimitive.content)
        assertEquals(seqBefore, stored.seq(), "a discarded write must not move the row in the change feed")
    }

    @Test
    fun an_equal_timestamp_write_is_accepted_and_takes_a_new_seq() = runTest {
        upsert("collection_entry", listOf(entry("m1", 50, "title" to "first")))
        val seqBefore = server.row("collection_entry", "alice", "m1")!!.seq()

        upsert("collection_entry", listOf(entry("m1", 50, "title" to "second")))

        val stored = server.row("collection_entry", "alice", "m1")!!
        assertEquals("second", stored.getValue("title").jsonPrimitive.content)
        assertTrue(stored.seq() > seqBefore)
    }

    @Test
    fun a_timestamp_ahead_of_the_server_clock_is_clamped_to_now_plus_the_slack() = runTest {
        upsert("collection_entry", listOf(entry("m1", serverNow + 86_400_000L)))

        assertEquals(serverNow + 60_000L, server.row("collection_entry", "alice", "m1")!!.getValue("updated_at_epoch_ms").jsonPrimitive.long)
    }

    @Test
    fun a_clamped_row_can_be_overwritten_once_the_server_clock_passes_it() = runTest {
        upsert("collection_entry", listOf(entry("m1", serverNow + 86_400_000L, "title" to "from a fast clock")))

        serverNow += 120_000L
        upsert("collection_entry", listOf(entry("m1", serverNow, "title" to "an honest edit")))

        assertEquals("an honest edit", server.row("collection_entry", "alice", "m1")!!.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun server_seq_comes_from_one_sequence_shared_by_every_table() = runTest {
        upsert("collection_entry", listOf(entry("m1", 10)))
        upsert(
            "episode_progress",
            listOf(JsonObject(mapOf("episode_id" to JsonPrimitive("e1"), "media_id" to JsonPrimitive("m1"), "season_number" to JsonPrimitive(1), "episode_number" to JsonPrimitive(1), "updated_at_epoch_ms" to JsonPrimitive(10)))),
        )

        val first = server.row("collection_entry", "alice", "m1")!!.seq()
        val second = server.row("episode_progress", "alice", "e1")!!.seq()

        assertTrue(second > first)
    }

    @Test
    fun an_upsert_onto_an_existing_row_leaves_a_gap_in_the_sequence() = runTest {
        upsert("collection_entry", listOf(entry("m1", 10)))
        val first = server.row("collection_entry", "alice", "m1")!!.seq()
        upsert("collection_entry", listOf(entry("m1", 20)))
        upsert("collection_entry", listOf(entry("m2", 20)))

        // The conflicting upsert drew a value in the insert trigger and another in the update trigger.
        assertEquals(first + 3, server.row("collection_entry", "alice", "m2")!!.seq())
    }

    @Test
    fun a_response_is_cut_at_max_rows_even_when_the_limit_asks_for_more() = runTest {
        server.maxRows = 3
        upsert("collection_entry", (1..10).map { entry("m$it", it.toLong()) })

        assertEquals(3, select("collection_entry").size)
        assertEquals(3, select("collection_entry", "&limit=500&order=server_seq.asc").size)
        assertEquals(2, select("collection_entry", "&limit=2").size)
    }

    @Test
    fun order_and_gt_filters_page_through_a_table_by_server_seq() = runTest {
        server.maxRows = 4
        upsert("collection_entry", (1..10).map { entry("m$it", it.toLong()) })

        val seen = mutableListOf<String>()
        var after = 0L
        while (true) {
            val page = select("collection_entry", "&order=server_seq.asc&limit=500&server_seq=gt.$after")
            if (page.isEmpty()) break
            seen += page.map { it.getValue("media_id").jsonPrimitive.content }
            after = page.last().seq()
        }

        assertEquals((1..10).map { "m$it" }.sorted(), seen.sorted())
        assertEquals(10, seen.size)
    }

    @Test
    fun without_an_order_the_result_is_in_key_order_not_change_order() = runTest {
        upsert("collection_entry", listOf(entry("b", 10), entry("a", 10)))
        upsert("collection_entry", listOf(entry("b", 20)))

        assertEquals(listOf("a", "b"), select("collection_entry").map { it.getValue("media_id").jsonPrimitive.content })
        assertEquals(listOf("a", "b"), select("collection_entry", "&order=server_seq.asc").map { it.getValue("media_id").jsonPrimitive.content })
    }

    @Test
    fun a_user_only_sees_and_overwrites_their_own_rows() = runTest {
        val bob = server.signUp("bob")
        upsert("collection_entry", listOf(entry("m1", 10, "title" to "alice's")))
        upsert("collection_entry", listOf(entry("m1", 10, "title" to "bob's")), bearer = bob)

        assertEquals(listOf("bob's"), select("collection_entry", bearer = bob).map { it.getValue("title").jsonPrimitive.content })
        assertEquals(listOf("alice's"), select("collection_entry").map { it.getValue("title").jsonPrimitive.content })
    }

    @Test
    fun an_unknown_or_expired_token_is_a_401() = runTest {
        server.expireAccessToken(token)

        val response = client.get("http://fake/rest/v1/collection_entry?select=*") {
            header("apikey", "anon")
            header("Authorization", "Bearer $token")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun an_unknown_column_and_a_null_in_a_not_null_column_are_rejected_with_400() = runTest {
        assertEquals(HttpStatusCode.BadRequest, upsert("collection_entry", listOf(entry("m1", 10, "nonexistent" to 1))).status)
        assertEquals(HttpStatusCode.BadRequest, upsert("collection_entry", listOf(entry("m1", 10, "title" to null))).status)
        assertEquals(0, server.rows("collection_entry", "alice").size, "a rejected request writes nothing")
    }

    @Test
    fun an_upsert_of_the_same_key_twice_in_one_body_is_rejected() = runTest {
        val response = upsert("collection_entry", listOf(entry("m1", 10), entry("m1", 20)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun a_plain_insert_onto_an_existing_key_is_a_conflict() = runTest {
        upsert("collection_entry", listOf(entry("m1", 10)))

        assertEquals(HttpStatusCode.BadRequest, upsert("collection_entry", listOf(entry("m1", 20)), merge = false).status)
    }

    @Test
    fun injected_failures_answer_with_their_status_and_run_out() = runTest {
        server.failWith(503, times = 1)

        assertEquals(HttpStatusCode.ServiceUnavailable, upsert("collection_entry", listOf(entry("m1", 10))).status)
        assertEquals(HttpStatusCode.NoContent, upsert("collection_entry", listOf(entry("m1", 10))).status)
    }

    @Test
    fun a_dropped_connection_is_an_exception_not_a_response() = runTest {
        server.dropConnection(times = 1)

        assertFailsWith<java.io.IOException> { upsert("collection_entry", listOf(entry("m1", 10))) }
    }

    @Test
    fun refreshing_rotates_both_tokens_and_a_used_refresh_token_is_dead() = runTest {
        val body = """{"refresh_token":"refresh-alice"}"""
        suspend fun redeem() = client.post("http://fake/auth/v1/token?grant_type=refresh_token") {
            header("apikey", "anon")
            setBody(io.ktor.http.content.TextContent(body, io.ktor.http.ContentType.Application.Json))
        }

        val first = redeem()
        assertEquals(HttpStatusCode.OK, first.status)
        val rotated = Json.parseToJsonElement(first.bodyAsText()) as JsonObject
        assertNotEquals("access-alice", rotated.getValue("access_token").jsonPrimitive.contentOrNull)

        assertEquals(HttpStatusCode.BadRequest, redeem().status)
    }

    @Test
    fun the_on_request_hook_runs_before_the_request_is_processed() = runTest {
        var seenBefore: Int? = null
        server.onRequest = { seenBefore = server.rows("collection_entry", "alice").size }

        upsert("collection_entry", listOf(entry("m1", 10)))

        assertEquals(0, seenBefore)
        assertEquals(1, server.rows("collection_entry", "alice").size)
    }
}
