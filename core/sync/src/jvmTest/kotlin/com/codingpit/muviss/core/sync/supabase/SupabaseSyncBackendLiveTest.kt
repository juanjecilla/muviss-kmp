package com.codingpit.muviss.core.sync.supabase

import com.codingpit.muviss.core.sync.CollectionEntryChange
import com.codingpit.muviss.core.sync.SyncBackendId
import com.codingpit.muviss.core.sync.SyncChangeSet
import com.codingpit.muviss.core.sync.SyncPage
import com.codingpit.muviss.core.sync.SyncSession
import com.codingpit.muviss.core.sync.SyncSessionExpiredException
import com.codingpit.muviss.core.sync.SyncSessionStore
import com.codingpit.muviss.core.sync.SyncTable
import com.codingpit.muviss.core.testing.FakeClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `SupabaseSyncBackend` over real HTTP, against the local Supabase stack
 * (`supabase start` from this repo's `supabase/` directory). Everything else in
 * the sync suites goes through `FakeSupabaseServer` or a canned `MockEngine`;
 * this is the one place the real client meets a real PostgREST and GoTrue.
 *
 * **Skipped unless `MUVISS_SUPABASE_LIVE=1`**, so the default run and CI never
 * touch it. Run it with the stack's values from `supabase status -o env`:
 *
 * ```
 * MUVISS_SUPABASE_LIVE=1 \
 * MUVISS_SUPABASE_URL=http://127.0.0.1:54321 \
 * MUVISS_SUPABASE_ANON_KEY=... \
 * MUVISS_SUPABASE_SERVICE_ROLE_KEY=... \
 * ./gradlew :core:sync:jvmTest --tests '*SupabaseSyncBackendLiveTest*' --rerun
 * ```
 *
 * It refuses any URL that is not loopback, and it fails (rather than skips)
 * when the gate is on but the URL or a key is missing. Each test creates its
 * own account through the admin API. Keys are read from the environment and
 * never written anywhere.
 */
class SupabaseSyncBackendLiveTest {

    @Test
    fun push_then_pull_round_trips_a_rating() = runBlocking {
        val rig = Rig.create(liveConfig())
        try {
            rig.backend.push(SyncChangeSet(collectionEntries = listOf(entry("tmdb:movie:603", rating = 5, updatedAt = 1_000)))).getOrThrow()
            assertEquals(listOf("tmdb:movie:603" to 5), rig.pullCollection().map { it.mediaId to it.rating })
        } finally {
            rig.close()
        }
    }

    @Test
    fun a_newer_null_rating_clears_the_column() = runBlocking {
        val rig = Rig.create(liveConfig())
        try {
            rig.backend.push(SyncChangeSet(collectionEntries = listOf(entry("tmdb:movie:1", rating = 3, updatedAt = 1_000)))).getOrThrow()
            rig.backend.push(SyncChangeSet(collectionEntries = listOf(entry("tmdb:movie:1", rating = null, updatedAt = 2_000)))).getOrThrow()
            assertEquals(listOf<Int?>(null), rig.pullCollection().map { it.rating })
        } finally {
            rig.close()
        }
    }

    @Test
    fun a_stale_write_is_accepted_but_the_newer_row_survives() = runBlocking {
        val rig = Rig.create(liveConfig())
        try {
            rig.backend.push(SyncChangeSet(collectionEntries = listOf(entry("tmdb:movie:2", rating = 4, updatedAt = 2_000)))).getOrThrow()
            // The trigger rewrites a stale NEW back to OLD, so the push still answers 2xx.
            rig.backend.push(SyncChangeSet(collectionEntries = listOf(entry("tmdb:movie:2", rating = 1, updatedAt = 1_000)))).getOrThrow()
            assertEquals(listOf<Int?>(4), rig.pullCollection().map { it.rating })
        } finally {
            rig.close()
        }
    }

    @Test
    fun thirty_rows_come_back_in_one_page_and_then_an_empty_request_ends_the_pull() = runBlocking {
        val rig = Rig.create(liveConfig())
        try {
            val rows = (1..30).map { entry("tmdb:movie:$it", rating = it % 5 + 1, updatedAt = 1_000) }
            rig.backend.push(SyncChangeSet(collectionEntries = rows)).getOrThrow()

            rig.requests.clear()
            val pages = mutableListOf<SyncPage>()
            rig.backend.pull(emptyMap()) { pages += it }.getOrThrow()

            val collectionPages = pages.filter { it.table == SyncTable.COLLECTION_ENTRY }
            assertEquals(1, collectionPages.size, "expected one page of collection rows")
            assertEquals(30, collectionPages.single().changes.collectionEntries.size)
            // One request returned the rows, a second came back empty and ended the drain (ADR 0020).
            assertEquals(2, rig.requests.count { it.contains(COLLECTION_REMOTE) }, "rows page, then an empty page")
        } finally {
            rig.close()
        }
    }

    @Test
    fun an_expired_access_token_is_refreshed_and_the_refresh_token_rotates() = runBlocking {
        val rig = Rig.create(liveConfig())
        try {
            // Expired on the device's clock, so the backend refreshes before the first request.
            rig.store.save(rig.signedIn.copy(accessToken = "expired-token", expiresAtEpochMs = 0))
            assertTrue(rig.pullCollection().isEmpty())

            val after = assertNotNull(rig.store.current, "the session was cleared, not refreshed")
            assertNotEquals("expired-token", after.accessToken)
            // A null replacement would pass assertNotEquals and leave a session that cannot refresh again.
            val rotated = assertNotNull(after.refreshToken, "the refreshed session has no refresh token")
            assertNotEquals(rig.signedIn.refreshToken, rotated, "GoTrue rotates the refresh token")
        } finally {
            rig.close()
        }
    }

    @Test
    fun a_garbage_token_with_no_refresh_token_is_refused_as_an_expired_session() = runBlocking {
        val rig = Rig.create(liveConfig())
        try {
            rig.store.save(rig.signedIn.copy(accessToken = "garbage-token", refreshToken = null, expiresAtEpochMs = null))
            val result = rig.backend.pull(emptyMap()) { }
            assertIs<SyncSessionExpiredException>(result.exceptionOrNull())
            Unit // JUnit 4 needs a void test; assertIs returns the exception
        } finally {
            rig.close()
        }
    }
}

private const val COLLECTION_REMOTE = "collection_entry"
private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]")

private data class Live(val url: String, val anon: String, val service: String)

/** Skips unless the gate is on; fails, not skips, when the gate is on and the setup is wrong. */
private fun liveConfig(): Live {
    assumeTrue("set MUVISS_SUPABASE_LIVE=1 to run against the local Supabase stack", System.getenv("MUVISS_SUPABASE_LIVE") == "1")
    val url = System.getenv("MUVISS_SUPABASE_URL")?.trimEnd('/') ?: error("MUVISS_SUPABASE_URL is required with MUVISS_SUPABASE_LIVE=1")
    val host = URI(url).host.orEmpty()
    check(host in LOOPBACK) { "MUVISS_SUPABASE_URL must be loopback (127.0.0.1, localhost or [::1]); this test creates accounts" }
    val anon = System.getenv("MUVISS_SUPABASE_ANON_KEY") ?: error("MUVISS_SUPABASE_ANON_KEY is required with MUVISS_SUPABASE_LIVE=1")
    val service = System.getenv("MUVISS_SUPABASE_SERVICE_ROLE_KEY") ?: error("MUVISS_SUPABASE_SERVICE_ROLE_KEY is required with MUVISS_SUPABASE_LIVE=1")
    return Live(url, anon, service)
}

private fun entry(mediaId: String, rating: Int?, updatedAt: Long) = CollectionEntryChange(
    mediaId = mediaId,
    mediaType = "MOVIE",
    title = "Live $mediaId",
    posterUrl = null,
    releaseYear = 1999,
    productionStatus = "RELEASED",
    totalEpisodes = 0,
    airedEpisodes = 0,
    favorite = false,
    genres = "",
    runtimeMinutes = null,
    addedAtEpochMs = updatedAt,
    updatedAtEpochMs = updatedAt,
    deleted = false,
    rating = rating,
    note = null,
)

private class InMemorySessionStore : SyncSessionStore {
    var current: SyncSession? = null
        private set

    override suspend fun load(): SyncSession? = current

    override suspend fun save(session: SyncSession) {
        current = session
    }

    override suspend fun clear() {
        current = null
    }
}

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    val user: UserRef,
)

@Serializable
private data class UserRef(val id: String)

/** A real HTTP client, one freshly created and signed-in account, and the backend over them. */
private class Rig private constructor(
    private val client: HttpClient,
    val store: InMemorySessionStore,
    val requests: MutableList<String>,
    val signedIn: SyncSession,
    val backend: SupabaseSyncBackend,
) {
    suspend fun pullCollection(): List<CollectionEntryChange> {
        val rows = mutableListOf<CollectionEntryChange>()
        backend.pull(emptyMap()) { page -> rows += page.changes.collectionEntries }.getOrThrow()
        return rows
    }

    fun close() = client.close()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun create(live: Live): Rig {
            val requests = mutableListOf<String>()
            val client = HttpClient(OkHttp) {
                install(ContentNegotiation) { json(json) }
                install(
                    createClientPlugin("LiveRequestLog") {
                        onRequest { request, _ -> requests += request.url.buildString() }
                    },
                )
            }
            // Every failure after this point must close the client; the caller never receives a Rig to close.
            var handedOver = false
            try {
                val email = "sync-live-${UUID.randomUUID()}@muviss.test"
                val password = "Live-${UUID.randomUUID()}-Aa1"

                val created = client.post("${live.url}/auth/v1/admin/users") {
                    header("apikey", live.service)
                    header(HttpHeaders.Authorization, "Bearer ${live.service}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"$email","password":"$password","email_confirm":true}""")
                }
                check(created.status.isSuccess()) { "admin user creation failed: ${created.status}" }

                val token = client.post("${live.url}/auth/v1/token?grant_type=password") {
                    header("apikey", live.anon)
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"$email","password":"$password"}""")
                }
                check(token.status.isSuccess()) { "password sign-in failed: ${token.status}" }
                val body = json.decodeFromString(TokenResponse.serializer(), token.bodyAsText())

                val session = SyncSession(
                    backendId = SyncBackendId.SUPABASE,
                    userId = body.user.id,
                    email = email,
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken,
                    expiresAtEpochMs = System.currentTimeMillis() + body.expiresIn * 1000,
                )
                val store = InMemorySessionStore().also { it.save(session) }
                val backend = SupabaseSyncBackend(client, live.url, live.anon, store, FakeClock(System.currentTimeMillis()))
                return Rig(client, store, requests, session, backend).also { handedOver = true }
            } finally {
                if (!handedOver) client.close()
            }
        }
    }
}
