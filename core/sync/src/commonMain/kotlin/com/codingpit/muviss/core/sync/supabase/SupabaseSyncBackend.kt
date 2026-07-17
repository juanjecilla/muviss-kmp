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
        val token = requireAccessToken()
        postgrest.upsert(TABLE_COLLECTION_ENTRY, token, changes.collectionEntries)
        postgrest.upsert(TABLE_EPISODE_PROGRESS, token, changes.episodeProgress)
        postgrest.upsert(TABLE_MEDIA_LIST, token, changes.mediaLists)
        postgrest.upsert(TABLE_LIST_ENTRY, token, changes.listEntries)
    }

    override suspend fun pull(sinceEpochMs: Long?): Result<SyncChangeSet> = runCatching {
        val token = requireAccessToken()
        SyncChangeSet(
            collectionEntries = postgrest.selectSince(TABLE_COLLECTION_ENTRY, token, sinceEpochMs),
            episodeProgress = postgrest.selectSince(TABLE_EPISODE_PROGRESS, token, sinceEpochMs),
            mediaLists = postgrest.selectSince(TABLE_MEDIA_LIST, token, sinceEpochMs),
            listEntries = postgrest.selectSince(TABLE_LIST_ENTRY, token, sinceEpochMs),
        )
    }

    private suspend fun ensureRestored() {
        if (restored) return
        restoreMutex.withLock {
            if (restored) return
            sessionState.value = sessionStore.load()
            restored = true
        }
    }

    private suspend fun requireAccessToken(): String {
        ensureRestored()
        return sessionState.value?.accessToken ?: error("Sync attempted while signed out")
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
        const val MILLIS_PER_SECOND = 1000L
    }
}
