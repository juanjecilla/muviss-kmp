package com.codingpit.muviss.core.sync.supabase

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent

/**
 * The Supabase Edge Functions this client calls (`/functions/v1/<name>`).
 * Today that is one: `delete-account` (ADR 0019, `supabase/functions/delete-account`).
 */
internal class SupabaseFunctionsClient(
    private val client: HttpClient,
    private val baseUrl: String,
    private val anonKey: String,
) {
    /**
     * `POST /functions/v1/delete-account` with the caller's own access token.
     * The function resolves the user from that token — it never takes a user
     * id from the request, so there is nothing else to send — and hard-deletes
     * them; every table cascades. 200 on success, 401 for a token that names
     * no live user.
     */
    suspend fun deleteAccount(accessToken: String) {
        client.post("$baseUrl/functions/v1/delete-account") {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            setBody(TextContent("{}", ContentType.Application.Json))
        }.ensureSuccess("account deletion")
    }
}
