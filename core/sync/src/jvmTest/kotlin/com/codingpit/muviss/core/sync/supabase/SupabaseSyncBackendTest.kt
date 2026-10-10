package com.codingpit.muviss.core.sync.supabase

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.sync.CollectionEntryChange
import com.codingpit.muviss.core.sync.OAuthProvider
import com.codingpit.muviss.core.sync.ServerEntitlement
import com.codingpit.muviss.core.sync.SyncBackendId
import com.codingpit.muviss.core.sync.SyncChangeSet
import com.codingpit.muviss.core.sync.SyncFailureReason
import com.codingpit.muviss.core.sync.SyncSession
import com.codingpit.muviss.core.sync.SyncSessionStore
import com.codingpit.muviss.core.sync.SyncWriteRefusedException
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first tests this class has ever had. `SupabaseSyncBackend` shipped with
 * ADR 0009 verified against nothing but the fake backend one layer above it,
 * which is exactly the layer that could not see any of what is checked here —
 * a Supabase response's HTTP status, and whether an expired token is ever
 * renewed. Both were broken; see ADR 0018.
 */
class SupabaseSyncBackendTest {

    private class RecordingClock(var millis: Long) : AppClock {
        override fun nowEpochMs(): Long = millis
    }

    private class InMemorySessionStore(private var session: SyncSession? = null) : SyncSessionStore {
        var cleared = false
        override suspend fun load(): SyncSession? = session
        override suspend fun save(session: SyncSession) {
            this.session = session
        }

        override suspend fun clear() {
            session = null
            cleared = true
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun sessionExpiringAt(expiresAtEpochMs: Long?, refreshToken: String? = "refresh-1") = SyncSession(
        backendId = SyncBackendId.SUPABASE,
        userId = "user-1",
        email = "person@example.com",
        accessToken = "expired-token",
        refreshToken = refreshToken,
        expiresAtEpochMs = expiresAtEpochMs,
    )

    private fun backend(
        store: SyncSessionStore,
        clock: AppClock,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Pair<SupabaseSyncBackend, MutableList<HttpRequestData>> {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return SupabaseSyncBackend(
            client = client,
            baseUrl = BASE_URL,
            anonKey = "anon-key",
            sessionStore = store,
            clock = clock,
        ) to requests
    }

    private fun MockRequestHandleScope.jsonOk(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private val sessionBody = """
        {"access_token":"fresh-token","refresh_token":"refresh-2","expires_in":3600,
         "user":{"id":"user-1","email":"person@example.com"}}
    """.trimIndent()

    private val oneDirtyEntry = SyncChangeSet(
        collectionEntries = listOf(
            CollectionEntryChange(
                mediaId = "tmdb:movie:603",
                mediaType = "MOVIE",
                title = "The Matrix",
                posterUrl = null,
                releaseYear = 1999,
                productionStatus = "RELEASED",
                totalEpisodes = 0,
                airedEpisodes = 0,
                favorite = false,
                genres = "",
                runtimeMinutes = null,
                addedAtEpochMs = 1L,
                updatedAtEpochMs = 2L,
                deleted = false,
                rating = null,
                note = null,
            ),
        ),
    )

    // --- HTTP status handling (A1) ---------------------------------------

    @Test
    fun a_rejected_push_fails_instead_of_reporting_success() = runTest {
        // The bug this pins down: upsert() returns Unit and Ktor's
        // expectSuccess is off for the shared client, so a 401 used to look
        // exactly like a 200. SyncEngine would then clear isDirty on rows the
        // server never accepted and never retry them.
        val (backend, _) = backend(InMemorySessionStore(sessionExpiringAt(null)), RecordingClock(1_000L)) {
            respond("""{"message":"JWT expired"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val result = backend.push(oneDirtyEntry)

        assertTrue(result.isFailure, "a 401 push must not be reported as a successful one")
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("401"),
            "the failure has to name the status, or the profile screen shows a message nobody can act on",
        )
    }

    @Test
    fun a_rejected_pull_fails_with_the_status_not_a_deserialization_error() = runTest {
        // PostgREST answers errors with a JSON object; decoding it as List<T>
        // would blow up complaining about the shape, never mentioning the 403.
        val (backend, _) = backend(InMemorySessionStore(sessionExpiringAt(null)), RecordingClock(1_000L)) {
            respond("""{"message":"permission denied"}""", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val result = backend.pull(emptyMap()) { }

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("403"), "expected the status in: $message")
        assertTrue(message.contains("permission denied"), "expected the server's own words in: $message")
    }

    @Test
    fun a_successful_push_succeeds() = runTest {
        val (backend, requests) = backend(InMemorySessionStore(sessionExpiringAt(null)), RecordingClock(1_000L)) {
            respond("", HttpStatusCode.Created)
        }

        assertTrue(backend.push(oneDirtyEntry).isSuccess)
        assertEquals(1, requests.size, "only the one non-empty table should be posted")
    }

    // --- Token refresh (A2) ----------------------------------------------

    @Test
    fun an_expiring_token_is_refreshed_before_the_request_that_would_have_failed() = runTest {
        // Without this, a session signed in on Monday stops syncing about an
        // hour later and only a manual re-login brings it back.
        val store = InMemorySessionStore(sessionExpiringAt(expiresAtEpochMs = 1_000L))
        val clock = RecordingClock(1_000L)
        val (backend, requests) = backend(store, clock) { request ->
            if (request.url.encodedPath.endsWith("/auth/v1/token")) jsonOk(sessionBody) else respond("", HttpStatusCode.Created)
        }

        assertTrue(backend.push(oneDirtyEntry).isSuccess)

        assertEquals("/auth/v1/token", requests.first().url.encodedPath)
        assertEquals("refresh_token", requests.first().url.parameters["grant_type"])
        assertEquals("fresh-token", backend.session.first()?.accessToken)
        assertEquals("refresh-2", backend.session.first()?.refreshToken, "GoTrue rotates the refresh token; the new one has to be the one kept")
        assertEquals(1_000L + 3_600_000L, backend.session.first()?.expiresAtEpochMs)
    }

    @Test
    fun a_token_with_time_left_is_used_as_is() = runTest {
        val store = InMemorySessionStore(sessionExpiringAt(expiresAtEpochMs = 10_000_000L))
        val (backend, requests) = backend(store, RecordingClock(1_000L)) { respond("", HttpStatusCode.Created) }

        backend.push(oneDirtyEntry)

        assertTrue(requests.none { it.url.encodedPath.endsWith("/auth/v1/token") }, "refreshing a live token is a wasted round trip on every sync")
    }

    @Test
    fun a_401_triggers_one_refresh_and_a_retry() = runTest {
        // Covers what the expiry stamp cannot predict: a wrong device clock, or
        // a token revoked server-side ahead of time.
        val store = InMemorySessionStore(sessionExpiringAt(expiresAtEpochMs = 10_000_000L))
        var pushAttempts = 0
        val (backend, _) = backend(store, RecordingClock(1_000L)) { request ->
            when {
                request.url.encodedPath.endsWith("/auth/v1/token") -> jsonOk(sessionBody)

                else -> {
                    pushAttempts++
                    if (pushAttempts == 1) respond("""{"message":"JWT expired"}""", HttpStatusCode.Unauthorized) else respond("", HttpStatusCode.Created)
                }
            }
        }

        assertTrue(backend.push(oneDirtyEntry).isSuccess, "the retry after refreshing should carry the request through")
        assertEquals(2, pushAttempts)
    }

    @Test
    fun a_401_on_a_token_with_time_left_really_renews_it() = runTest {
        // The renewal used to re-check the expiry stamp under its lock, find
        // time left, and hand the rejected token straight back — so the retry
        // after a 401 on an early-revoked token was the same request again.
        val store = InMemorySessionStore(sessionExpiringAt(expiresAtEpochMs = 10_000_000L))
        val (backend, requests) = backend(store, RecordingClock(1_000L)) { request ->
            when {
                request.url.encodedPath.endsWith("/auth/v1/token") -> jsonOk(sessionBody)
                request.headers[HttpHeaders.Authorization] == "Bearer expired-token" -> respond("""{"message":"JWT expired"}""", HttpStatusCode.Unauthorized)
                else -> respond("", HttpStatusCode.Created)
            }
        }

        assertTrue(backend.push(oneDirtyEntry).isSuccess)
        assertEquals("Bearer fresh-token", requests.last().headers[HttpHeaders.Authorization])
        assertEquals(1, requests.count { it.url.encodedPath.endsWith("/auth/v1/token") })
    }

    // --- The paid gate (ADR 0019) -------------------------------------------

    @Test
    fun a_push_refused_by_row_level_security_is_a_write_refusal() = runTest {
        val (backend, _) = backend(InMemorySessionStore(sessionExpiringAt(null)), RecordingClock(1_000L)) {
            respond(
                """{"code":"42501","details":null,"hint":null,"message":"new row violates row-level security policy for table \"collection_entry\""}""",
                HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val failure = backend.push(oneDirtyEntry).exceptionOrNull()

        assertIs<SyncWriteRefusedException>(failure)
        assertEquals(SyncFailureReason.Unauthorised, SyncFailureReason.classify(failure), "when it is not the paywall it still reads as a refusal")
    }

    @Test
    fun a_403_without_the_rls_code_is_an_ordinary_failure() = runTest {
        val (backend, _) = backend(InMemorySessionStore(sessionExpiringAt(null)), RecordingClock(1_000L)) {
            respond("""{"message":"forbidden by a proxy"}""", HttpStatusCode.Forbidden)
        }

        assertIs<SupabaseHttpException>(backend.push(oneDirtyEntry).exceptionOrNull())
    }

    @Test
    fun the_grant_is_read_from_the_tokens_sync_until_claim() = runTest {
        val withClaim = sessionExpiringAt(null).copy(accessToken = FakeSupabaseServer.accessToken("user-1", syncUntil = "2026-11-09T00:16:15Z"))
        val (backend, requests) = backend(InMemorySessionStore(withClaim), RecordingClock(1_000L)) { respond("", HttpStatusCode.Created) }

        assertEquals(1_794_183_375_000L, backend.syncGrantedUntil())
        assertTrue(requests.isEmpty(), "the fast path never touches the network")
    }

    @Test
    fun a_token_without_the_claim_grants_nothing_and_signed_out_neither() = runTest {
        val (backend, _) = backend(InMemorySessionStore(sessionExpiringAt(null)), RecordingClock(1_000L)) { respond("", HttpStatusCode.Created) }
        assertNull(backend.syncGrantedUntil(), "expired-token is not even a JWT")

        val (signedOut, _) = backend(InMemorySessionStore(), RecordingClock(1_000L)) { respond("", HttpStatusCode.Created) }
        assertNull(signedOut.syncGrantedUntil())
    }

    @Test
    fun the_entitlement_row_is_read_with_postgrests_timestamp_format() = runTest {
        val (backend, requests) = backend(InMemorySessionStore(sessionExpiringAt(null)), RecordingClock(1_000L)) {
            jsonOk("""[{"active":true,"expires_at":"2026-11-09T00:16:15.289+00:00"}]""")
        }

        assertEquals(ServerEntitlement(active = true, expiresAtEpochMs = 1_794_183_375_289L), backend.fetchEntitlement().getOrThrow())
        assertEquals("/rest/v1/entitlement", requests.single().url.encodedPath)
        assertEquals("Bearer expired-token", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun no_entitlement_row_is_no_entitlement() = runTest {
        val (backend, _) = backend(InMemorySessionStore(sessionExpiringAt(null)), RecordingClock(1_000L)) { jsonOk("[]") }

        assertNull(backend.fetchEntitlement().getOrThrow())
    }

    @Test
    fun refreshing_on_demand_renews_a_token_that_has_time_left() = runTest {
        val store = InMemorySessionStore(sessionExpiringAt(expiresAtEpochMs = 10_000_000L))
        val (backend, _) = backend(store, RecordingClock(1_000L)) { jsonOk(sessionBody) }

        assertEquals("fresh-token", backend.refreshSession().getOrThrow().accessToken)
        assertEquals("fresh-token", backend.session.first()?.accessToken)
    }

    @Test
    fun deleting_the_account_calls_the_function_with_the_callers_token_and_signs_out_locally() = runTest {
        val store = InMemorySessionStore(sessionExpiringAt(null))
        val (backend, requests) = backend(store, RecordingClock(1_000L)) { jsonOk("""{"deleted":"user-1"}""") }

        assertTrue(backend.deleteAccount().isSuccess)

        val request = requests.single()
        assertEquals("/functions/v1/delete-account", request.url.encodedPath)
        assertEquals("POST", request.method.value)
        assertEquals("Bearer expired-token", request.headers[HttpHeaders.Authorization])
        assertEquals("anon-key", request.headers["apikey"])
        assertNull(backend.session.first())
        assertTrue(store.cleared)
    }

    @Test
    fun a_failed_deletion_keeps_the_session() = runTest {
        val store = InMemorySessionStore(sessionExpiringAt(null))
        val (backend, _) = backend(store, RecordingClock(1_000L)) { respond("""{"error":"delete failed"}""", HttpStatusCode.InternalServerError) }

        assertTrue(backend.deleteAccount().isFailure)
        assertNotNull(backend.session.first())
    }

    @Test
    fun a_failed_refresh_signs_the_user_out_rather_than_looping() = runTest {
        // GoTrue single-uses refresh tokens, so once one is rejected every
        // later attempt fails identically. Dropping to signed-out puts the
        // sign-in button back, which is the only thing that can recover.
        val store = InMemorySessionStore(sessionExpiringAt(expiresAtEpochMs = 1_000L))
        val (backend, _) = backend(store, RecordingClock(1_000L)) {
            respond("""{"message":"Invalid Refresh Token"}""", HttpStatusCode.BadRequest)
        }

        assertTrue(backend.push(oneDirtyEntry).isFailure)
        assertNull(backend.session.first())
        assertTrue(store.cleared, "the dead session must not survive a restart")
    }

    @Test
    fun an_expired_session_with_no_refresh_token_signs_out() = runTest {
        val store = InMemorySessionStore(sessionExpiringAt(expiresAtEpochMs = 1_000L, refreshToken = null))
        val (backend, _) = backend(store, RecordingClock(1_000L)) { respond("", HttpStatusCode.Created) }

        assertTrue(backend.push(oneDirtyEntry).isFailure)
        assertNull(backend.session.first())
    }

    // --- Anonymous sign-in (#98) ------------------------------------------

    @Test
    fun signing_in_anonymously_sends_its_empty_body_through_real_content_negotiation() = runTest {
        // Pins down #98: EmptyBody was file-private, which the JVM's reflective
        // access check for kotlinx.serialization's generated INSTANCE lookup
        // rejected with IllegalAccessException — only visible once a real
        // ContentNegotiation client (not a hand-built request) serializes it.
        val (backend, requests) = backend(InMemorySessionStore(), RecordingClock(1_000L)) { jsonOk(sessionBody) }

        val result = backend.signInAnonymously()

        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")
        assertEquals("/auth/v1/signup", requests.single().url.encodedPath)
    }

    // --- OAuth (ADR 0014) ------------------------------------------------

    @Test
    fun signing_in_stamps_an_absolute_expiry_from_the_relative_one() = runTest {
        val clock = RecordingClock(50_000L)
        val (backend, _) = backend(InMemorySessionStore(), clock) { jsonOk(sessionBody) }

        backend.beginOAuth(OAuthProvider.GITHUB, REDIRECT)
        val session = backend.completeOAuth("auth-code").getOrNull()

        assertNotNull(session)
        assertEquals(50_000L + 3_600_000L, session.expiresAtEpochMs, "expires_in is relative; only an absolute stamp survives being persisted")
    }

    @Test
    fun the_authorize_url_carries_the_provider_redirect_and_a_hashed_challenge() = runTest {
        val (backend, _) = backend(InMemorySessionStore(), RecordingClock(1_000L)) { jsonOk(sessionBody) }

        val url = backend.beginOAuth(OAuthProvider.GITHUB, REDIRECT).getOrThrow()

        assertTrue(url.startsWith("$BASE_URL/auth/v1/authorize"), "unexpected endpoint: $url")
        assertTrue(url.contains("provider=github"), "missing provider: $url")
        assertTrue(url.contains("code_challenge="), "missing challenge: $url")
        // s256, never `plain` — `plain` sends the verifier itself, which
        // protects nothing against an intercepted redirect.
        assertTrue(url.contains("code_challenge_method=s256"), "missing or wrong method: $url")
        assertFalse(url.contains(" "), "the redirect must be url-encoded: $url")
    }

    @Test
    fun the_exchange_sends_the_verifier_that_produced_the_challenge() = runTest {
        val (backend, requests) = backend(InMemorySessionStore(), RecordingClock(1_000L)) { jsonOk(sessionBody) }

        val url = backend.beginOAuth(OAuthProvider.GITHUB, REDIRECT).getOrThrow()
        backend.completeOAuth("auth-code").getOrThrow()

        val exchange = requests.last()
        assertEquals("pkce", exchange.url.parameters["grant_type"])
        // The verifier itself never appears in the browser URL — only its hash
        // does. That asymmetry is the whole mechanism.
        val body = (exchange.body as TextContent).text
        assertTrue(body.contains("\"auth_code\":\"auth-code\""), "unexpected body: $body")
        assertTrue(body.contains("code_verifier"), "unexpected body: $body")
        val verifier = Regex("\"code_verifier\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        assertFalse(url.contains(verifier), "the verifier must not travel in the authorize URL")
    }

    @Test
    fun completing_without_starting_fails_rather_than_sending_a_blank_verifier() = runTest {
        val (backend, requests) = backend(InMemorySessionStore(), RecordingClock(1_000L)) { jsonOk(sessionBody) }

        val result = backend.completeOAuth("auth-code")

        assertTrue(result.isFailure, "a redirect with no attempt behind it is not a sign-in")
        assertTrue(requests.isEmpty(), "nothing should reach the network")
    }

    @Test
    fun a_verifier_is_single_use() = runTest {
        val (backend, _) = backend(InMemorySessionStore(), RecordingClock(1_000L)) { jsonOk(sessionBody) }
        backend.beginOAuth(OAuthProvider.GITHUB, REDIRECT)
        backend.completeOAuth("auth-code").getOrThrow()

        val second = backend.completeOAuth("another-code")

        assertTrue(second.isFailure, "replaying a redirect must not redeem a second code against the same verifier")
    }

    @Test
    fun a_rejected_exchange_is_reported_and_clears_the_attempt() = runTest {
        val (backend, _) = backend(InMemorySessionStore(), RecordingClock(1_000L)) {
            respond("""{"error":"invalid_grant","error_description":"code challenge does not match"}""", HttpStatusCode.BadRequest)
        }
        backend.beginOAuth(OAuthProvider.GITHUB, REDIRECT)

        val result = backend.completeOAuth("auth-code")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("code challenge does not match"))
        assertTrue(backend.completeOAuth("auth-code").isFailure, "a burnt verifier must not be retried")
    }

    private companion object {
        const val BASE_URL = "https://project.supabase.co"
        const val REDIRECT = "muviss://auth-callback"
    }
}
