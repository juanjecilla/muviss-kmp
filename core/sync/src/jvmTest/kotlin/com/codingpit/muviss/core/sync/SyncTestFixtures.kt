package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared by this module's `SyncEngine` tests (mirrors `feature/collection/data`'s `CollectionDataTestFixtures`). */
internal class ImmediateDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

internal class FakeClock(private var millis: Long) : AppClock {
    override fun nowEpochMs(): Long = millis
    fun advanceTo(newMillis: Long) {
        millis = newMillis
    }
}

internal val FAKE_SESSION = SyncSession(
    backendId = SyncBackendId.SUPABASE,
    userId = "user-1",
    email = "person@example.com",
    accessToken = "token",
    refreshToken = "refresh",
    expiresAtEpochMs = null,
)

/**
 * In-memory [SyncBackend] standing in for a real Supabase project (no live
 * calls in tests — see ADR 0009). [push] applies the same last-write-wins
 * guard the real project's Postgres trigger does (docs/SYNC.md): an
 * incoming row only overwrites what's stored when its `updatedAtEpochMs` is
 * at least as new, so a stale push from one "device" can never clobber a
 * newer row another "device" already pushed — this is what makes the
 * two-way convergence test meaningful regardless of push order.
 */
internal class FakeSyncBackend(
    session: SyncSession? = FAKE_SESSION,
) : SyncBackend {
    override val id: SyncBackendId = SyncBackendId.SUPABASE

    private val sessionState = MutableStateFlow(session)
    override val session: StateFlow<SyncSession?> = sessionState

    private val collectionEntries = mutableMapOf<String, CollectionEntryChange>()
    private val episodeProgress = mutableMapOf<String, EpisodeProgressChange>()
    private val mediaLists = mutableMapOf<String, MediaListChange>()
    private val listEntries = mutableMapOf<Pair<String, String>, ListEntryChange>()
    private val triageDecisions = mutableMapOf<String, TriageDecisionChange>()

    var pushFailure: Throwable? = null
    var pullFailure: Throwable? = null

    fun setSession(session: SyncSession?) {
        sessionState.value = session
    }

    fun seedRemoteCollectionEntry(change: CollectionEntryChange) {
        collectionEntries[change.mediaId] = change
    }

    fun seedRemoteTriageDecision(change: TriageDecisionChange) {
        triageDecisions[change.mediaId] = change
    }

    fun remoteTriageDecision(mediaId: String): TriageDecisionChange? = triageDecisions[mediaId]

    override suspend fun signInAnonymously(): Result<SyncSession> = Result.success(FAKE_SESSION).also { sessionState.value = FAKE_SESSION }

    override suspend fun requestEmailOtp(email: String): Result<Unit> = Result.success(Unit)

    override suspend fun verifyEmailOtp(email: String, code: String): Result<SyncSession> {
        val upgraded = FAKE_SESSION.copy(email = email)
        sessionState.value = upgraded
        return Result.success(upgraded)
    }

    override suspend fun signOut() {
        sessionState.value = null
    }

    override suspend fun push(changes: SyncChangeSet): Result<Unit> {
        pushFailure?.let { return Result.failure(it) }
        changes.collectionEntries.forEach { upsertIfNewer(collectionEntries, it.mediaId, it) { c -> c.updatedAtEpochMs } }
        changes.episodeProgress.forEach { upsertIfNewer(episodeProgress, it.episodeId, it) { c -> c.updatedAtEpochMs } }
        changes.mediaLists.forEach { upsertIfNewer(mediaLists, it.id, it) { c -> c.updatedAtEpochMs } }
        changes.listEntries.forEach { upsertIfNewer(listEntries, it.listId to it.mediaId, it) { c -> c.updatedAtEpochMs } }
        changes.triageDecisions.forEach { upsertIfNewer(triageDecisions, it.mediaId, it) { c -> c.updatedAtEpochMs } }
        return Result.success(Unit)
    }

    override suspend fun pull(sinceEpochMs: Long?): Result<SyncChangeSet> {
        pullFailure?.let { return Result.failure(it) }
        val since = sinceEpochMs ?: Long.MIN_VALUE
        return Result.success(
            SyncChangeSet(
                collectionEntries = collectionEntries.values.filter { it.updatedAtEpochMs > since },
                episodeProgress = episodeProgress.values.filter { it.updatedAtEpochMs > since },
                mediaLists = mediaLists.values.filter { it.updatedAtEpochMs > since },
                listEntries = listEntries.values.filter { it.updatedAtEpochMs > since },
                triageDecisions = triageDecisions.values.filter { it.updatedAtEpochMs > since },
            ),
        )
    }

    private fun <K, V> upsertIfNewer(map: MutableMap<K, V>, key: K, value: V, timestampOf: (V) -> Long) {
        val existing = map[key]
        if (existing == null || timestampOf(value) >= timestampOf(existing)) {
            map[key] = value
        }
    }
}
