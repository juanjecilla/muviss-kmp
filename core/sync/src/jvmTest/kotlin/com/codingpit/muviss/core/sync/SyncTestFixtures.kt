package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared by this module's `SyncEngine` tests (mirrors `feature/collection/data`'s `CollectionDataTestFixtures`). */
internal class ImmediateDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

/**
 * Records that [userId] already owns what is in this database, as it does once
 * a device has synced at all. A fresh database has no owner, and the first
 * sync for an owner puts every local row on the change-log (ADR 0020) — right
 * for a first sync, and the reason a test about *clean* rows has to say the
 * device has been here before.
 */
internal suspend fun MuvissDatabase.alreadyOwnedBy(userId: String = FAKE_SESSION.userId) {
    syncStateQueries.ensureRow()
    syncStateQueries.setOwner(userId)
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
 * In-memory [SyncBackend] for the tests that are about the *engine* rather
 * than the wire: it hands the engine exactly the rows, pages and failures a
 * test dictates, without HTTP in the way. Anything about what actually goes on
 * the wire (nulls, chunking, `max_rows`, token refresh) belongs against
 * `FakeSupabaseServer` instead, because this fake never serialises and never
 * truncates and so cannot fail in those ways.
 *
 * [push] applies the same last-write-wins guard the real project's Postgres
 * trigger does (docs/SYNC.md): an incoming row only overwrites what's stored
 * when its `updatedAtEpochMs` is at least as new, so a stale push from one
 * "device" can never clobber a newer row another "device" already pushed. Every
 * accepted write takes the next value of one shared sequence, and [pull] pages
 * through a table in that order — the same contract the real backend keeps.
 */
internal class FakeSyncBackend(
    session: SyncSession? = FAKE_SESSION,
) : SyncBackend {
    override val id: SyncBackendId = SyncBackendId.SUPABASE

    private val sessionState = MutableStateFlow(session)
    override val session: StateFlow<SyncSession?> = sessionState

    private class Stored<V>(val value: V, val seq: Long)

    private var nextSeq = 1L
    private val collectionEntries = mutableMapOf<String, Stored<CollectionEntryChange>>()
    private val episodeProgress = mutableMapOf<String, Stored<EpisodeProgressChange>>()
    private val mediaLists = mutableMapOf<String, Stored<MediaListChange>>()
    private val listEntries = mutableMapOf<Pair<String, String>, Stored<ListEntryChange>>()
    private val triageDecisions = mutableMapOf<String, Stored<TriageDecisionChange>>()
    private val triageSnoozes = mutableMapOf<String, Stored<TriageSnoozeChange>>()
    private val episodePlays = mutableMapOf<String, Stored<EpisodePlayChange>>()

    var pushFailure: Throwable? = null

    /** When set, the Nth call to [push] (1-based) fails and every other succeeds — a failure partway through a chunked push. */
    var failPushOnCall: Int? = null
    var pullFailure: Throwable? = null

    /** Rows per page [pull] delivers. Small values make a test see several pages without needing a large library. */
    var pullPageSize: Int = 500

    /** When set, [pull] delivers this many pages and then fails — an interruption partway through a pull. */
    var failPullAfterPages: Int? = null

    /** Every [push] this backend has been handed, oldest first — lets a test assert that two overlapping cycles produced one push, not two. */
    val pushes = mutableListOf<SyncChangeSet>()

    /** The cursors each [pull] was called with, oldest first. */
    val pullRequests = mutableListOf<Map<SyncTable, SyncCursor>>()

    /**
     * Holds [push] open until completed. Without a way to keep one cycle
     * suspended mid-flight, a "two cycles overlap" test does not overlap
     * anything — the calls just run one after the other and pass whether or
     * not `SyncEngine` actually excludes them.
     */
    var pushGate: CompletableDeferred<Unit>? = null

    fun setSession(session: SyncSession?) {
        sessionState.value = session
    }

    fun seedRemoteCollectionEntry(change: CollectionEntryChange) {
        collectionEntries[change.mediaId] = Stored(change, nextSeq++)
    }

    fun seedRemoteEpisodeProgress(change: EpisodeProgressChange) {
        episodeProgress[change.episodeId] = Stored(change, nextSeq++)
    }

    fun seedRemoteTriageDecision(change: TriageDecisionChange) {
        triageDecisions[change.mediaId] = Stored(change, nextSeq++)
    }

    fun remoteTriageDecision(mediaId: String): TriageDecisionChange? = triageDecisions[mediaId]?.value

    fun seedRemoteTriageSnooze(change: TriageSnoozeChange) {
        triageSnoozes[change.mediaId] = Stored(change, nextSeq++)
    }

    fun remoteTriageSnooze(mediaId: String): TriageSnoozeChange? = triageSnoozes[mediaId]?.value

    fun seedRemoteEpisodePlay(change: EpisodePlayChange) {
        episodePlays[change.id] = Stored(change, nextSeq++)
    }

    fun remoteEpisodePlay(id: String): EpisodePlayChange? = episodePlays[id]?.value

    override suspend fun signInAnonymously(): Result<SyncSession> = Result.success(FAKE_SESSION).also { sessionState.value = FAKE_SESSION }

    override suspend fun beginOAuth(provider: OAuthProvider, redirectUri: String): Result<String> = Result.success("https://fake.test/auth/v1/authorize?provider=${provider.wireName}&redirect_to=$redirectUri")

    override suspend fun completeOAuth(authCode: String): Result<SyncSession> {
        val signedIn = FAKE_SESSION.copy(email = "person@example.com")
        sessionState.value = signedIn
        return Result.success(signedIn)
    }

    override suspend fun signOut() {
        sessionState.value = null
    }

    override suspend fun push(changes: SyncChangeSet): Result<Unit> {
        pushes += changes
        pushGate?.await()
        pushFailure?.let { return Result.failure(it) }
        if (failPushOnCall == pushes.size) return Result.failure(IllegalStateException("connection lost on push #${pushes.size}"))
        changes.collectionEntries.forEach { upsertIfNewer(collectionEntries, it.mediaId, it) { c -> c.updatedAtEpochMs } }
        changes.episodeProgress.forEach { upsertIfNewer(episodeProgress, it.episodeId, it) { c -> c.updatedAtEpochMs } }
        changes.mediaLists.forEach { upsertIfNewer(mediaLists, it.id, it) { c -> c.updatedAtEpochMs } }
        changes.listEntries.forEach { upsertIfNewer(listEntries, it.listId to it.mediaId, it) { c -> c.updatedAtEpochMs } }
        changes.triageDecisions.forEach { upsertIfNewer(triageDecisions, it.mediaId, it) { c -> c.updatedAtEpochMs } }
        changes.triageSnoozes.forEach { upsertIfNewer(triageSnoozes, it.mediaId, it) { c -> c.updatedAtEpochMs } }
        changes.episodePlays.forEach { upsertIfNewer(episodePlays, it.id, it) { c -> c.updatedAtEpochMs } }
        return Result.success(Unit)
    }

    override suspend fun pull(after: Map<SyncTable, SyncCursor>, onPage: suspend (SyncPage) -> Unit): Result<Unit> {
        pullRequests += after
        pullFailure?.let { return Result.failure(it) }
        var delivered = 0
        for (table in SyncTable.entries) {
            val start = after[table]?.position ?: 0L
            val pages = when (table) {
                SyncTable.COLLECTION_ENTRY -> pagesOf(collectionEntries.values, start) { SyncChangeSet(collectionEntries = it) }
                SyncTable.EPISODE_PROGRESS -> pagesOf(episodeProgress.values, start) { SyncChangeSet(episodeProgress = it) }
                SyncTable.MEDIA_LIST -> pagesOf(mediaLists.values, start) { SyncChangeSet(mediaLists = it) }
                SyncTable.LIST_ENTRY -> pagesOf(listEntries.values, start) { SyncChangeSet(listEntries = it) }
                SyncTable.TRIAGE_DECISION -> pagesOf(triageDecisions.values, start) { SyncChangeSet(triageDecisions = it) }
                SyncTable.TRIAGE_SNOOZE -> pagesOf(triageSnoozes.values, start) { SyncChangeSet(triageSnoozes = it) }
                SyncTable.EPISODE_PLAY -> pagesOf(episodePlays.values, start) { SyncChangeSet(episodePlays = it) }
            }
            for ((changes, cursor) in pages) {
                if (failPullAfterPages != null && delivered >= failPullAfterPages!!) return Result.failure(IllegalStateException("connection lost mid-pull"))
                onPage(SyncPage(table, changes, cursor))
                delivered++
            }
        }
        return Result.success(Unit)
    }

    private fun <V> pagesOf(stored: Collection<Stored<V>>, after: Long, wrap: (List<V>) -> SyncChangeSet): List<Pair<SyncChangeSet, SyncCursor>> = stored
        .filter { it.seq > after }
        .sortedBy { it.seq }
        .chunked(pullPageSize)
        .map { page -> wrap(page.map { it.value }) to SyncCursor(page.last().seq) }

    private fun <K, V> upsertIfNewer(map: MutableMap<K, Stored<V>>, key: K, value: V, timestampOf: (V) -> Long) {
        val existing = map[key]
        if (existing == null || timestampOf(value) >= timestampOf(existing.value)) {
            map[key] = Stored(value, nextSeq++)
        }
    }
}
