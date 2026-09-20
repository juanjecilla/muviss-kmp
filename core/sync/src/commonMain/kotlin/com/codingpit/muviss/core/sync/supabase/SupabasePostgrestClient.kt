package com.codingpit.muviss.core.sync.supabase

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One pulled row and the `server_seq` PostgREST stamped it with — kept beside the row, not in it, so the change types and the wire format gain no field. */
internal class PulledRow<T>(val serverSeq: Long, val value: T)

/**
 * Thin wrapper over Supabase's PostgREST table endpoints
 * (`/rest/v1/<table>`), generic over the row type so [upsert]/[selectPage]
 * work for every synced table without one near-identical copy each. Every
 * table's Postgres row carries `user_id`, populated by a `DEFAULT auth.uid()`
 * column server-side (see docs/SYNC.md's schema) — the client never sends it
 * explicitly, RLS is what actually enforces a user only ever sees/writes
 * their own rows.
 *
 * Last-write-wins on push is enforced by a Postgres trigger, not here (see
 * docs/SYNC.md): this client's [upsert] is a plain
 * `Prefer: resolution=merge-duplicates` upsert, safe to call unconditionally
 * because the trigger silently discards a stale write rather than this
 * client having to read-before-write.
 *
 * **Both directions bypass the shared client's content negotiation** and use
 * a `Json` configured here. The shared client is configured for TMDB, whose
 * responses want `explicitNulls = false` — and that setting is exactly wrong
 * for a push: PostgREST builds its `ON CONFLICT DO UPDATE SET` from the union
 * of keys in the body, so a field left out of *every* object in a batch is not
 * written and a cleared rating stays on the server. That configuration is not
 * ours to change (`:core:network` is shared), so sync owns its wire format.
 */
internal class SupabasePostgrestClient(
    private val client: HttpClient,
    private val baseUrl: String,
    private val anonKey: String,
) {
    /**
     * Posts [rows] as one upsert. Nulls are written out — `"rating":null`
     * clears the column; leaving the key off would not.
     */
    suspend fun <T> upsert(table: String, accessToken: String, rows: List<T>, serializer: KSerializer<T>) {
        if (rows.isEmpty()) return
        val body = pushJson.encodeToString(ListSerializer(serializer), rows)
        client.post("$baseUrl/rest/v1/$table") {
            applyHeaders(accessToken)
            header(HttpHeaders.Prefer, "resolution=merge-duplicates,return=minimal")
            setBody(TextContent(body, ContentType.Application.Json))
        }.ensureSuccess("upsert into $table")
    }

    /**
     * One page of [table], ordered by the server-assigned `server_seq`, of the
     * rows after [afterSeq]. May return fewer than [limit] rows even when more
     * exist — a server-side `max_rows` cuts the response silently — so an
     * empty page is the only sign a table is drained.
     *
     * `server_seq` is read from the raw JSON rather than decoded into [T]: it
     * is a transport detail of this backend, and the change types are what the
     * rest of the app and its tests build by hand.
     */
    suspend fun <T> selectPage(table: String, accessToken: String, afterSeq: Long, limit: Int, serializer: KSerializer<T>): List<PulledRow<T>> {
        val response = client.get("$baseUrl/rest/v1/$table") {
            applyHeaders(accessToken)
            parameter("select", "*")
            parameter("order", "server_seq.asc")
            parameter("limit", limit)
            parameter("server_seq", "gt.$afterSeq")
        }
        // Before parsing, not after: PostgREST answers an error with a JSON
        // *object*, so reading it as an array would fail with a message that
        // names neither the status nor the cause.
        response.ensureSuccess("select from $table")
        val array = pullJson.parseToJsonElement(response.bodyAsText()) as? JsonArray ?: error("select from $table did not return a JSON array")
        return array.map { element -> pulledRow(table, element, serializer) }
    }

    private fun <T> pulledRow(table: String, element: JsonElement, serializer: KSerializer<T>): PulledRow<T> {
        val row = element as? JsonObject ?: error("select from $table returned a non-object row")
        val seq = row["server_seq"]?.jsonPrimitive?.longOrNull
            ?: error("$table has no server_seq column: apply the sync_server_seq migration (supabase db push)")
        return PulledRow(seq, pullJson.decodeFromJsonElement(serializer, row))
    }

    private fun HttpRequestBuilder.applyHeaders(accessToken: String) {
        header("apikey", anonKey)
        header(HttpHeaders.Authorization, "Bearer $accessToken")
    }

    private companion object {
        /** `explicitNulls` is the point: see the class KDoc. */
        val pushJson = Json {
            explicitNulls = true
            encodeDefaults = true
        }

        /** PostgREST also returns `user_id` and `server_seq`, which no change type declares. */
        val pullJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    }
}
