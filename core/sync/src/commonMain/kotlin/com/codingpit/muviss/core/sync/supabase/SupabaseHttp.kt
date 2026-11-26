package com.codingpit.muviss.core.sync.supabase

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * A non-2xx response from Supabase, carrying enough to tell an expired token
 * (401) apart from an RLS refusal (403) or a project that is simply down
 * (5xx) — [SupabaseSyncBackend] keys its refresh-and-retry off [status], and
 * `SyncOutcome.Failed` surfaces [message] to the profile screen.
 */
internal class SupabaseHttpException(
    val status: Int,
    what: String,
    body: String,
) : RuntimeException("Supabase $what failed with HTTP $status${if (body.isBlank()) "" else ": $body"}") {
    /**
     * PostgREST's error `code` (a Postgres SQLSTATE such as `42501`, or a
     * `PGRSTxxx`), or null when the body carries none. Read with a pattern
     * rather than a JSON parse because the body was truncated to
     * [ERROR_BODY_LIMIT] and may no longer be valid JSON.
     */
    val postgrestCode: String? = POSTGREST_CODE.find(body)?.groupValues?.get(1)

    /**
     * A row-level-security refusal: HTTP 403 with SQLSTATE `42501`
     * (`insufficient_privilege`). On a synced table that is what a push gets
     * when the token carries no live `sync_until` claim (ADR 0019) — and also
     * what a forged co-watch row gets, which is why the status and code alone
     * never decide that a refusal is the paywall.
     */
    val isRowLevelSecurityRefusal: Boolean get() = status == HTTP_FORBIDDEN && postgrestCode == SQLSTATE_INSUFFICIENT_PRIVILEGE
}

private val POSTGREST_CODE = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"")
private const val SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501"

/**
 * Ktor's `expectSuccess` is off for the shared client (`:core:network`'s
 * `createHttpClient`, which TMDB also uses and which does its own error
 * handling), so a non-2xx response arrives here looking exactly like a
 * successful one. Left unchecked that is not a cosmetic bug: `upsert`
 * returns `Unit`, so a rejected push would report success, `SyncEngine`
 * would clear `isDirty` on rows the server never accepted, and nothing
 * would ever retry them — the library silently diverges and a fresh install
 * restores it incomplete.
 */
internal suspend fun HttpResponse.ensureSuccess(what: String) {
    if (status.isSuccess()) return
    val body = runCatching { bodyAsText() }.getOrDefault("")
    throw SupabaseHttpException(status = status.value, what = what, body = body.take(ERROR_BODY_LIMIT))
}

/** Enough to identify a PostgREST/GoTrue error object; these are never large, but a 5xx HTML page can be. */
private const val ERROR_BODY_LIMIT = 500

internal const val HTTP_BAD_REQUEST = 400
internal const val HTTP_UNAUTHORIZED = 401
internal const val HTTP_FORBIDDEN = 403
