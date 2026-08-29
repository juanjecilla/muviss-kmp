package com.codingpit.muviss.core.sync.supabase

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncBackendId
import com.codingpit.muviss.core.sync.SyncChangeSet
import com.codingpit.muviss.core.sync.SyncSession
import com.codingpit.muviss.core.sync.SyncSessionStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    override val session: Flow<SyncSession?> = flow {
        ensureRestored()
        emitAll(sessionState)
    }

    override suspend fun signInAnonymously(): Result<SyncSession> = runCatching {
        auth.signInAnonymously().toSession().also { persist(it) }
    }

    override suspend fun requestEmailOtp(email: String): Result<Unit> = runCatching {
        auth.requestEmailOtp(email)
    }

    override suspend fun verifyEmailOtp(email: String, code: String): Result<SyncSession> = runCatching {
        auth.verifyEmailOtp(email, code).toSession().also { persist(it) }
    }

    override suspend fun signOut() {
        ensureRestored()
        sessionState.value?.let { current -> runCatching { auth.signOut(current.accessToken) } }
        sessionState.value = null
        sessionStore.clear()
    }

    override suspend fun push(changes: SyncChangeSet): Result<Unit> = runCatching {
        withAccessToken { token ->
            postgrest.upsert(TABLE_COLLECTION_ENTRY, token, changes.collectionEntries)
            postgrest.upsert(TABLE_EPISODE_PROGRESS, token, changes.episodeProgress)
            postgrest.upsert(TABLE_MEDIA_LIST, token, changes.mediaLists)
            postgrest.upsert(TABLE_LIST_ENTRY, token, changes.listEntries)
            postgrest.upsert(TABLE_TRIAGE_DECISION, token, changes.triageDecisions)
            postgrest.upsert(TABLE_EPISODE_PLAY, token, changes.episodePlays)
        }
    }

    override suspend fun pull(sinceEpochMs: Long?): Result<SyncChangeSet> = runCatching {
        withAccessToken { token ->
            SyncChangeSet(
                collectionEntries = postgrest.selectSince(TABLE_COLLECTION_ENTRY, token, sinceEpochMs),
                episodeProgress = postgrest.selectSince(TABLE_EPISODE_PROGRESS, token, sinceEpochMs),
                mediaLists = postgrest.selectSince(TABLE_MEDIA_LIST, token, sinceEpochMs),
                listEntries = postgrest.selectSince(TABLE_LIST_ENTRY, token, sinceEpochMs),
                triageDecisions = postgrest.selectSince(TABLE_TRIAGE_DECISION, token, sinceEpochMs),
                episodePlays = postgrest.selectSince(TABLE_EPISODE_PLAY, token, sinceEpochMs),
            )
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
     * A failed refresh clears the session rather than leaving a dead one in
     * place: the refresh token is single-use and GoTrue has already
     * invalidated it, so every later attempt would fail the same way. Dropping
     * to signed-out puts the profile screen back on the sign-in button, which
     * is the only thing that can actually recover.
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
            error("Sync session expired and carries no refresh token; sign in again")
        }
        val refreshed = runCatching { auth.refreshSession(refreshToken).toSession() }
            .getOrElse { failure ->
                clearSession()
                throw failure
            }
        persist(refreshed)
        refreshed
    }

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
