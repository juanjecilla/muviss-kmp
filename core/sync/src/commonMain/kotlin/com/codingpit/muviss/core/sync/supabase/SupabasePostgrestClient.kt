package com.codingpit.muviss.core.sync.supabase

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/**
 * Thin wrapper over Supabase's PostgREST table endpoints
 * (`/rest/v1/<table>`), generic over the row type so [upsert]/[selectSince]
 * work for all four synced tables without four near-identical copies. Every
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
 */
internal class SupabasePostgrestClient(
    private val client: HttpClient,
    private val baseUrl: String,
    private val anonKey: String,
) {
    suspend inline fun <reified T> upsert(table: String, accessToken: String, rows: List<T>) {
        if (rows.isEmpty()) return
        client.post("$baseUrl/rest/v1/$table") {
            applyHeaders(accessToken)
            header(HttpHeaders.Prefer, "resolution=merge-duplicates,return=minimal")
            setBody(rows)
        }
    }

    suspend inline fun <reified T> selectSince(table: String, accessToken: String, sinceEpochMs: Long?): List<T> = client.get("$baseUrl/rest/v1/$table") {
        applyHeaders(accessToken)
        parameter("select", "*")
        if (sinceEpochMs != null) {
            parameter("updated_at_epoch_ms", "gt.$sinceEpochMs")
        }
    }.body()

    fun HttpRequestBuilder.applyHeaders(accessToken: String) {
        header("apikey", anonKey)
        header(HttpHeaders.Authorization, "Bearer $accessToken")
        contentType(ContentType.Application.Json)
    }
}
