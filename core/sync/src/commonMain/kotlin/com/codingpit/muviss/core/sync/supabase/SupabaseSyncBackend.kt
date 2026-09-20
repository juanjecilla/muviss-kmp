package com.codingpit.muviss.core.sync.supabase

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.sync.CollectionEntryChange
import com.codingpit.muviss.core.sync.EpisodePlayChange
import com.codingpit.muviss.core.sync.EpisodeProgressChange
import com.codingpit.muviss.core.sync.ListEntryChange
import com.codingpit.muviss.core.sync.MediaListChange
import com.codingpit.muviss.core.sync.OAuthProvider
import com.codingpit.muviss.core.sync.PULL_PAGE_ROWS
import com.codingpit.muviss.core.sync.PUSH_CHUNK_ROWS
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncBackendId
import com.codingpit.muviss.core.sync.SyncChangeSet
import com.codingpit.muviss.core.sync.SyncCursor
import com.codingpit.muviss.core.sync.SyncPage
import com.codingpit.muviss.core.sync.SyncSession
import com.codingpit.muviss.core.sync.SyncSessionExpiredException
import com.codingpit.muviss.core.sync.SyncSessionStore
import com.codingpit.muviss.core.sync.SyncTable
import com.codingpit.muviss.core.sync.TriageDecisionChange
import com.codingpit.muviss.core.sync.newPkcePair
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer

/**
 * Supabase implementation of [SyncBackend]: Ktor against PostgREST +
 * GoTrue's REST APIs directly rather than the `supabase-kt` SDK (ADR 0009 —
 * this is ~6 endpoints total, and `supabase-kt`'s wasmJs target was beta at
 * time of writing).
 *
 * Session restoration from [sessionStore] is deferred to the first access
 * of [session] (via [ensureRestored], a `Mutex`-guarded lazy suspend init)
 * rather than done eagerly in an `init` block, the same pattern
 * `DatabaseFactory.web.kt`'s `SchemaEnsuringDriver` uses for the same
 * reason: reading from SQLDelight is suspend-shaped (`generateAsync`, ADR
 * 0008's amendment), and constructors can't call suspend functions.
 */
internal class SupabaseSyncBackend(
    client: HttpClient,
    baseUrl: String,
    anonKey: String,
    private val sessionStore: SyncSessionStore,
    private val clock: AppClock,
) : SyncBackend {

    override val id: SyncBackendId = SyncBackendId.SUPABASE

    private val auth = SupabaseAuthClient(client, baseUrl, anonKey)
    private val postgrest = SupabasePostgrestClient(client, baseUrl, anonKey)

    private val sessionState = MutableStateFlow<SyncSession?>(null)
    private val restoreMutex = Mutex()
    private val refreshMutex = Mutex()
    private var restored = false

    /** The PKCE verifier for the OAuth attempt currently in flight, if any. See [beginOAuth]. */
    private var pendingVerifier: String? = null

    override val session: Flow<SyncSession?> = flow {
        ensureRestored()
        emitAll(sessionState)
    }

    override suspend fun signInAnonymously(): Result<SyncSession> = runCatching {
        auth.signInAnonymously().toSession().also { persist(it) }
    }

    override suspend fun beginOAuth(provider: OAuthProvider, redirectUri: String): Result<String> = runCatching {
        val (verifier, challenge) = newPkcePair()
        // Kept in memory only. Persisting it would mean another migration for
        // a value that lives for the seconds the browser is open; the cost is
        // that a process death mid-sign-in loses the attempt, which surfaces
        // as completeOAuth's "no sign-in is in progress" and is fixed by
        // tapping the button again.
        pendingVerifier = verifier
        auth.authorizeUrl(provider = provider, redirectUri = redirectUri, codeChallenge = challenge)
    }

    override suspend fun completeOAuth(authCode: String): Result<SyncSession> = runCatching {
        val verifier = pendingVerifier ?: error("No sign-in is in progress — start again from the profile screen")
        val session = auth.exchangeOAuthCode(authCode = authCode, codeVerifier = verifier).toSession()
        // Single-use, whether or not the exchange succeeded: a verifier that
        // has been sent once must never be reused against a second code.
        pendingVerifier = null
        persist(session)
        session
    }.onFailure { pendingVerifier = null }

    override suspend fun signOut() {
        ensureRestored()
        sessionState.value?.let { current -> runCatching { auth.signOut(current.accessToken) } }
        sessionState.value = null
        sessionStore.clear()
    }

    /**
     * Parents before children, and at most [PUSH_CHUNK_ROWS] rows per request.
     * Each request refreshes and retries on its own (see [withAccessToken]) —
     * retrying the whole push after a 401 on chunk seven would re-send the six
     * that already landed.
     */
    override suspend fun push(changes: SyncChangeSet): Result<Unit> = runCatching {
        pushRows(TABLE_COLLECTION_ENTRY, changes.collectionEntries, CollectionEntryChange.serializer())
        pushRows(TABLE_EPISODE_PROGRESS, changes.episodeProgress, EpisodeProgressChange.serializer())
        pushRows(TABLE_MEDIA_LIST, changes.mediaLists, MediaListChange.serializer())
        pushRows(TABLE_LIST_ENTRY, changes.listEntries, ListEntryChange.serializer())
        pushRows(TABLE_TRIAGE_DECISION, changes.triageDecisions, TriageDecisionChange.serializer())
        pushRows(TABLE_EPISODE_PLAY, changes.episodePlays, EpisodePlayChange.serializer())
    }

    private suspend fun <T> pushRows(table: String, rows: List<T>, serializer: KSerializer<T>) {
        rows.chunked(PUSH_CHUNK_ROWS).forEach { chunk ->
            withAccessToken { token -> postgrest.upsert(table, token, chunk, serializer) }
        }
    }

    override suspend fun pull(after: Map<SyncTable, SyncCursor>, onPage: suspend (SyncPage) -> Unit): Result<Unit> = runCatching {
        for (table in SyncTable.entries) {
            val start = after[table]?.position ?: 0L
            when (table) {
                SyncTable.COLLECTION_ENTRY -> drain(RemoteTable(table, TABLE_COLLECTION_ENTRY, CollectionEntryChange.serializer()) { SyncChangeSet(collectionEntries = it) }, start, onPage)
                SyncTable.EPISODE_PROGRESS -> drain(RemoteTable(table, TABLE_EPISODE_PROGRESS, EpisodeProgressChange.serializer()) { SyncChangeSet(episodeProgress = it) }, start, onPage)
                SyncTable.MEDIA_LIST -> drain(RemoteTable(table, TABLE_MEDIA_LIST, MediaListChange.serializer()) { SyncChangeSet(mediaLists = it) }, start, onPage)
                SyncTable.LIST_ENTRY -> drain(RemoteTable(table, TABLE_LIST_ENTRY, ListEntryChange.serializer()) { SyncChangeSet(listEntries = it) }, start, onPage)
                SyncTable.TRIAGE_DECISION -> drain(RemoteTable(table, TABLE_TRIAGE_DECISION, TriageDecisionChange.serializer()) { SyncChangeSet(triageDecisions = it) }, start, onPage)
                SyncTable.EPISODE_PLAY -> drain(RemoteTable(table, TABLE_EPISODE_PLAY, EpisodePlayChange.serializer()) { SyncChangeSet(episodePlays = it) }, start, onPage)
            }
        }
    }

    /** One synced table as this backend addresses it: its seam name, its PostgREST name, and how its rows decode and wrap into a [SyncChangeSet]. */
    private class RemoteTable<T>(
        val table: SyncTable,
        val remoteName: String,
        val serializer: KSerializer<T>,
        val wrap: (List<T>) -> SyncChangeSet,
    )

    /**
     * Pages through one table by `server_seq` until a page comes back **empty**.
     *
     * Not until one comes back short: Supabase's `max_rows` cuts a response
     * silently, at a value this client cannot know, so a page smaller than
     * [PULL_PAGE_ROWS] is as likely to mean "capped" as "finished". Stopping on
     * a short page would silently drop everything past the cap. It costs one
     * extra, empty request per table per sync.
     *
     * The order is verified rather than trusted: a server that ignored `order`
     * would hand back an arbitrary subset, and taking its highest `server_seq`
     * as the cursor would skip the rest for good.
     */
    private suspend fun <T> drain(remote: RemoteTable<T>, start: Long, onPage: suspend (SyncPage) -> Unit) {
        var position = start
        while (true) {
            val rows = withAccessToken { token -> postgrest.selectPage(remote.remoteName, token, position, PULL_PAGE_ROWS, remote.serializer) }
            if (rows.isEmpty()) return
            val ascending = rows.zipWithNext().all { (previous, next) -> previous.serverSeq < next.serverSeq }
            check(ascending && rows.first().serverSeq > position) { "${remote.remoteName} was not returned in ascending server_seq order past $position" }
            position = rows.last().serverSeq
            onPage(SyncPage(remote.table, remote.wrap(rows.map { it.value }), SyncCursor(position)))
        }
    }

    private suspend fun ensureRestored() {
        if (restored) return
        restoreMutex.withLock {
            if (restored) return
            sessionState.value = sessionStore.load()
            restored = true
        }
    }

    /**
     * Runs [block] with a live access token, refreshing first if the stored
     * one is about to expire and once more if the server rejects it anyway.
     *
     * Both halves are needed. The proactive half is what keeps day-to-day
     * sync alive, since Supabase tokens last about an hour; the reactive
     * half covers the cases the expiry stamp cannot predict — a device clock
     * that is wrong, or a token revoked server-side before its time. Retrying
     * is safe because both callers are idempotent: PostgREST upserts
     * `merge-duplicates`, and a pull is a read.
     */
    private suspend fun <T> withAccessToken(block: suspend (String) -> T): T {
        val token = requireAccessToken()
        return try {
            block(token)
        } catch (e: SupabaseHttpException) {
            if (e.status != HTTP_UNAUTHORIZED) throw e
            block(refreshSession().accessToken)
        }
    }

    private suspend fun requireAccessToken(): String {
        ensureRestored()
        val current = sessionState.value ?: error("Sync attempted while signed out")
        val expiresAt = current.expiresAtEpochMs ?: return current.accessToken
        if (clock.nowEpochMs() < expiresAt - REFRESH_LEEWAY_MS) return current.accessToken
        return refreshSession().accessToken
    }

    /**
     * A refresh that GoTrue *rejects* clears the session: the refresh token is
     * single-use and already invalidated, so every later attempt would fail the
     * same way, and dropping to signed-out puts the sign-in button back, which
     * is the only thing that can recover.
     *
     * A refresh that merely *fails* does not. A dropped connection, a timeout
     * or a 5xx says nothing about the token, and signing the user out for it
     * turned every stretch offline into a forced re-login — the more often
     * sync runs unattended, the more often that would have fired. The session
     * stays, the error propagates, and the next attempt tries again.
     */
    private suspend fun refreshSession(): SyncSession = refreshMutex.withLock {
        ensureRestored()
        val current = sessionState.value ?: error("Sync attempted while signed out")
        // Another caller may have refreshed while this one waited on the lock.
        val expiresAt = current.expiresAtEpochMs
        if (expiresAt != null && clock.nowEpochMs() < expiresAt - REFRESH_LEEWAY_MS) return@withLock current
        val refreshToken = current.refreshToken
        if (refreshToken == null) {
            clearSession()
            throw SyncSessionExpiredException()
        }
        val refreshed = try {
            auth.refreshSession(refreshToken).toSession()
        } catch (rejected: SupabaseHttpException) {
            // Anything else — a dropped connection, a timeout, a cancellation —
            // is not caught here and leaves the session alone.
            if (rejected.isAuthRejection()) {
                clearSession()
                throw SyncSessionExpiredException(rejected)
            }
            throw rejected
        }
        persist(refreshed)
        refreshed
    }

    private fun SupabaseHttpException.isAuthRejection(): Boolean = status == HTTP_BAD_REQUEST || status == HTTP_UNAUTHORIZED

    private suspend fun clearSession() {
        sessionState.value = null
        sessionStore.clear()
        restored = true
    }

    private suspend fun persist(session: SyncSession) {
        sessionState.value = session
        sessionStore.save(session)
        restored = true
    }

    private fun GoTrueSessionDto.toSession(): SyncSession = SyncSession(
        backendId = SyncBackendId.SUPABASE,
        userId = user.id,
        email = user.email,
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochMs = expiresInSeconds?.let { seconds -> clock.nowEpochMs() + seconds * MILLIS_PER_SECOND },
    )

    private companion object {
        const val TABLE_COLLECTION_ENTRY = "collection_entry"
        const val TABLE_EPISODE_PROGRESS = "episode_progress"
        const val TABLE_MEDIA_LIST = "media_list"
        const val TABLE_LIST_ENTRY = "list_entry"
        const val TABLE_TRIAGE_DECISION = "triage_decision"
        const val TABLE_EPISODE_PLAY = "episode_play"
        const val MILLIS_PER_SECOND = 1000L

        /** Refresh a minute early rather than at the stroke of expiry, so a request in flight cannot age out mid-round-trip. */
        const val REFRESH_LEEWAY_MS = 60_000L
    }
}

/**
 * Builds the real Supabase backend over a caller-supplied [client]. The class
 * itself stays `internal`; this exists so a test outside `:core:sync` (the
 * app-level automatic-sync suites in `:app:shared`) can run the *real* backend
 * over a `FakeSupabaseServer`'s `MockEngine` instead of a stub that never
 * serialises a request. Production wiring goes through `syncModule`.
 */
fun createSupabaseSyncBackend(
    client: HttpClient,
    baseUrl: String,
    anonKey: String,
    sessionStore: SyncSessionStore,
    clock: AppClock,
): SyncBackend = SupabaseSyncBackend(client, baseUrl, anonKey, sessionStore, clock)
