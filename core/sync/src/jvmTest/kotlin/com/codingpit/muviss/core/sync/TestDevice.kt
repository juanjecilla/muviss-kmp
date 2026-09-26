@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.database.CollectionEntry
import com.codingpit.muviss.core.database.EpisodePlay
import com.codingpit.muviss.core.database.EpisodeProgress
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.sync.supabase.SupabaseSyncBackend
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json

internal const val TEST_BASE_URL = "https://project.supabase.co"

/**
 * The shared client configuration the *app* builds for Supabase.
 *
 * Deliberately mirrors `:core:network`'s `createHttpClient` — same
 * `ContentNegotiation` with `explicitNulls = false` — rather than the
 * convenient defaults a test would otherwise pick. The bug this exists to
 * catch (a cleared column that never reaches the server) is a property of that
 * exact configuration; a test client with `explicitNulls = true` would
 * silently paper over it. If `muvissJson` changes, change this to match.
 */
internal fun productionLikeClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                explicitNulls = false
            },
        )
    }
}

internal class InMemorySessionStore(private var session: SyncSession? = null) : SyncSessionStore {
    var cleared = false
        private set

    override suspend fun load(): SyncSession? = session

    override suspend fun save(session: SyncSession) {
        this.session = session
    }

    override suspend fun clear() {
        session = null
        cleared = true
    }
}

internal fun sessionFor(userId: String, accessToken: String = "access-$userId", refreshToken: String? = "refresh-$userId", expiresAtEpochMs: Long? = null) = SyncSession(
    backendId = SyncBackendId.SUPABASE,
    userId = userId,
    email = "$userId@example.com",
    accessToken = accessToken,
    refreshToken = refreshToken,
    expiresAtEpochMs = expiresAtEpochMs,
)

/**
 * One installation of the app: its own database, its own clock and its own
 * session, running the *real* [SupabaseSyncBackend] and the *real*
 * [SyncEngine] against a [FakeSupabaseServer] it shares with the other
 * devices in the test.
 */
internal class TestDevice(
    val server: FakeSupabaseServer,
    val userId: String = "alice",
    startMillis: Long = 1_000L,
    wrapDriver: (SqlDriver) -> SqlDriver = { it },
) {
    val clock = FakeClock(startMillis)
    var sessionStore = InMemorySessionStore(sessionFor(userId).also { server.signUp(userId) })
        private set
    val driver: SqlDriver = wrapDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)).also { MuvissDatabase.Schema.synchronous().create(it) }
    val database = MuvissDatabase(driver)
    internal var backend = newBackend()
        private set
    var engine = newEngine()
        private set

    private fun newBackend() = SupabaseSyncBackend(
        client = productionLikeClient(server.engine),
        baseUrl = TEST_BASE_URL,
        anonKey = "anon-key",
        sessionStore = sessionStore,
        clock = clock,
    )

    private fun newEngine() = SyncEngine(backend, database, ImmediateDispatchers(UnconfinedTestDispatcher()), clock)

    /**
     * Signs the person out and a different one in on this same installation:
     * same database, new session, and a fresh engine and backend as a process
     * restart would give. The database is deliberately left as it was.
     */
    fun signInAs(newUserId: String) {
        server.signUp(newUserId)
        sessionStore = InMemorySessionStore(sessionFor(newUserId))
        backend = newBackend()
        engine = newEngine()
    }

    suspend fun sync(): SyncOutcome = engine.syncNow()

    // --- local writes, as the repositories would leave them ---------------

    @Suppress("LongParameterList") // a row builder: every column is a named default a test may override
    suspend fun saveEntry(
        mediaId: String,
        title: String = "Title of $mediaId",
        updatedAt: Long = clock.nowEpochMs(),
        favorite: Boolean = false,
        rating: Int? = null,
        note: String? = null,
        posterUrl: String? = null,
        releaseYear: Int? = null,
        runtimeMinutes: Int? = null,
        airedEpisodes: Int = 0,
        totalEpisodes: Int = 0,
        mediaType: String = "MOVIE",
        isDirty: Boolean = true,
        deleted: Boolean = false,
    ) {
        database.collectionEntryQueries.upsert(
            mediaId = mediaId,
            mediaType = mediaType,
            title = title,
            posterUrl = posterUrl,
            releaseYear = releaseYear?.toLong(),
            productionStatus = "RELEASED",
            totalEpisodes = totalEpisodes.toLong(),
            airedEpisodes = airedEpisodes.toLong(),
            favorite = favorite,
            genres = "",
            runtimeMinutes = runtimeMinutes?.toLong(),
            addedAtEpochMs = 500L,
            updatedAtEpochMs = updatedAt,
            isDirty = isDirty,
            deleted = deleted,
            notificationsMuted = false,
            rating = rating?.toLong(),
            note = note,
            revisitWillingness = null,
            coWatchPinned = false,
        )
    }

    @Suppress("LongParameterList") // a row builder: every column is a named default a test may override
    suspend fun tick(
        mediaId: String,
        season: Int = 1,
        episode: Int,
        seen: Boolean = true,
        updatedAt: Long = clock.nowEpochMs(),
        isDirty: Boolean = true,
    ) {
        database.episodeProgressQueries.upsert(
            episodeId = "$mediaId/$season/$episode",
            mediaId = mediaId,
            seasonNumber = season.toLong(),
            episodeNumber = episode.toLong(),
            seen = seen,
            updatedAtEpochMs = updatedAt,
            isDirty = isDirty,
        )
    }

    @Suppress("LongParameterList") // a row builder: every column is a named default a test may override
    suspend fun play(
        mediaId: String,
        season: Int = 1,
        episode: Int,
        watchedAt: Long,
        updatedAt: Long = watchedAt,
        isDirty: Boolean = true,
        deleted: Boolean = false,
    ) {
        val episodeId = "$mediaId/$season/$episode"
        database.episodePlayQueries.upsert(
            id = "$episodeId@$watchedAt",
            episodeId = episodeId,
            mediaId = mediaId,
            watchedAtEpochMs = watchedAt,
            updatedAtEpochMs = updatedAt,
            isDirty = isDirty,
            deleted = deleted,
        )
    }

    // --- local reads --------------------------------------------------------

    fun entry(mediaId: String): CollectionEntry? = database.collectionEntryQueries.selectById(mediaId).executeAsOneOrNull()

    fun progress(mediaId: String): List<EpisodeProgress> = database.episodeProgressQueries.selectForMedia(mediaId).executeAsList()

    fun allProgress(): List<EpisodeProgress> = database.episodeProgressQueries.selectAll().executeAsList()

    fun livePlays(mediaId: String): List<EpisodePlay> = database.episodePlayQueries.selectAll().executeAsList().filter { it.mediaId == mediaId }
}
