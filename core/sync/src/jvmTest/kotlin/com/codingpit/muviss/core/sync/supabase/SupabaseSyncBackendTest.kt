package com.codingpit.muviss.core.sync.supabase

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.sync.CollectionEntryChange
import com.codingpit.muviss.core.sync.SyncBackendId
import com.codingpit.muviss.core.sync.SyncChangeSet
import com.codingpit.muviss.core.sync.SyncSession
import com.codingpit.muviss.core.sync.SyncSessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first tests this class has ever had. `SupabaseSyncBackend` shipped with
 * ADR 0009 verified against nothing but the fake backend one layer above it,
 * which is exactly the layer that could not see any of what is checked here —
 * a Supabase response's HTTP status, and whether an expired token is ever
 * renewed. Both were broken; see ADR 0012.
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

        val result = backend.pull(sinceEpochMs = null)

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

    @Test
    fun signing_in_stamps_an_absolute_expiry_from_the_relative_one() = runTest {
        val clock = RecordingClock(50_000L)
        val (backend, _) = backend(InMemorySessionStore(), clock) { jsonOk(sessionBody) }

        val session = backend.verifyEmailOtp("person@example.com", "123456").getOrNull()

        assertNotNull(session)
        assertEquals(50_000L + 3_600_000L, session.expiresAtEpochMs, "expires_in is relative; only an absolute stamp survives being persisted")
    }

    @Test
    fun a_wrong_code_is_reported_as_a_failure() = runTest {
        val (backend, _) = backend(InMemorySessionStore(), RecordingClock(1_000L)) {
            respond("""{"message":"Token has expired or is invalid"}""", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val result = backend.verifyEmailOtp("person@example.com", "000000")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Token has expired or is invalid"))
    }

    private companion object {
        const val BASE_URL = "https://project.supabase.co"
    }
}
