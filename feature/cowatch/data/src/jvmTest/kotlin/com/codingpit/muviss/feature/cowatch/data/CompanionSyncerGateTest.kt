@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.cowatch.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.sync.OAuthProvider
import com.codingpit.muviss.core.sync.ServerEntitlement
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncBackendId
import com.codingpit.muviss.core.sync.SyncChangeSet
import com.codingpit.muviss.core.sync.SyncCursor
import com.codingpit.muviss.core.sync.SyncPage
import com.codingpit.muviss.core.sync.SyncSession
import com.codingpit.muviss.core.sync.SyncTable
import com.codingpit.muviss.core.sync.SyncWriteRefusedException
import com.codingpit.muviss.core.sync.companion.CompanionBackend
import com.codingpit.muviss.core.sync.companion.CompanionChangeSet
import com.codingpit.muviss.core.sync.companion.CompanionPage
import com.codingpit.muviss.core.sync.companion.CompanionTable
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class GateDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

/** A signed-in session whose grant is [grantedUntil] (null: no `sync_until` claim); a renewal grants [renewedGrant]. */
private class GateSyncBackend(var grantedUntil: Long?, private val renewedGrant: Long? = null) : SyncBackend {
    private val current = MutableStateFlow<SyncSession?>(
        SyncSession(SyncBackendId.SUPABASE, "me", null, "access", null, null),
    )
    var refreshes = 0
    override val id = SyncBackendId.SUPABASE
    override val session: Flow<SyncSession?> = current

    override suspend fun syncGrantedUntil(): Long? = grantedUntil

    override suspend fun refreshSession(): Result<SyncSession> {
        refreshes++
        grantedUntil = renewedGrant
        return Result.success(current.value!!)
    }

    override suspend fun signInAnonymously(): Result<SyncSession> = error("not used by the syncer")
    override suspend fun beginOAuth(provider: OAuthProvider, redirectUri: String): Result<String> = error("not used by the syncer")
    override suspend fun completeOAuth(authCode: String): Result<SyncSession> = error("not used by the syncer")
    override suspend fun signOut() = Unit
    override suspend fun push(changes: SyncChangeSet): Result<Unit> = error("not used by the syncer")
    override suspend fun pull(after: Map<SyncTable, SyncCursor>, onPage: suspend (SyncPage) -> Unit): Result<Unit> = error("not used by the syncer")
    override suspend fun fetchEntitlement(): Result<ServerEntitlement?> = error("not used by the syncer")
    override suspend fun deleteAccount(): Result<Unit> = error("not used by the syncer")
}

/** The co-watch transport, scripted: [onPush] and [onPull] run first, so a test can make the grant lapse at that moment. */
private class ScriptedCompanionBackend(
    var pushResult: Result<Unit> = Result.success(Unit),
    var onPush: () -> Unit = {},
    var onPull: () -> Unit = {},
) : CompanionBackend {
    var pushes = 0
    var pulls = 0

    override suspend fun push(changes: CompanionChangeSet): Result<Unit> {
        pushes++
        onPush()
        return pushResult
    }

    override suspend fun pull(
        after: Map<CompanionTable, SyncCursor>,
        onPage: suspend (CompanionPage) -> Unit,
    ): Result<Unit> {
        pulls++
        onPull()
        return Result.success(Unit)
    }
}

/** #285: the co-watch syncer applies the same `sync_until` gate as the library engine (ADR 0019). */
class CompanionSyncerGateTest {

    private lateinit var database: MuvissDatabase
    private val clock = FakeClock(1_000L)
    private val live = 10_000L
    private val lapsed: Long? = null

    @BeforeTest
    fun setUp() {
        database = inMemoryDatabase()
    }

    private fun syncer(backend: SyncBackend, companion: CompanionBackend) = CompanionSyncer(
        database = database,
        backend = backend,
        companionBackend = companion,
        dispatchers = GateDispatchers(UnconfinedTestDispatcher()),
        clock = clock,
    )

    /** One dirty link, so a push has something to send. */
    private suspend fun dirtyLink() {
        database.companionLinkQueries.upsert(
            companionUserId = "friend",
            localName = null,
            nonce = "nonce",
            state = SqlDelightCoWatchRepository.STATE_INVITED,
            remoteState = null,
            createdAtEpochMs = 500L,
            updatedAtEpochMs = 600L,
            isDirty = true,
            deleted = false,
        )
    }

    @Test
    fun `an unpaid account with nothing to renew is not entitled and touches neither push nor pull`() = runTest {
        val backend = GateSyncBackend(grantedUntil = lapsed)
        val companion = ScriptedCompanionBackend()

        val result = syncer(backend, companion).syncNow().getOrThrow()

        assertEquals(CompanionSyncResult.NotEntitled, result)
        assertEquals(1, backend.refreshes, "a stale token is renewed once before giving up")
        assertEquals(0, companion.pushes)
        assertEquals(0, companion.pulls, "an unpaid pull is 200 [] and must not read as synced")
    }

    @Test
    fun `a renewal that carries the grant lets the cycle run`() = runTest {
        val backend = GateSyncBackend(grantedUntil = lapsed, renewedGrant = live)
        val companion = ScriptedCompanionBackend()

        assertEquals(CompanionSyncResult.Synced, syncer(backend, companion).syncNow().getOrThrow())
        assertEquals(1, companion.pulls)
    }

    @Test
    fun `a push refused after the grant lapsed is the paywall and pulls nothing`() = runTest {
        dirtyLink()
        val backend = GateSyncBackend(grantedUntil = live)
        val companion = ScriptedCompanionBackend(
            pushResult = Result.failure(SyncWriteRefusedException(IllegalStateException("403 42501"))),
            onPush = { backend.grantedUntil = lapsed },
        )

        assertEquals(CompanionSyncResult.NotEntitled, syncer(backend, companion).syncNow().getOrThrow())
        assertEquals(0, companion.pulls)
    }

    @Test
    fun `a push refused under a live grant stays a failure`() = runTest {
        dirtyLink()
        val backend = GateSyncBackend(grantedUntil = live)
        val companion = ScriptedCompanionBackend(
            pushResult = Result.failure(SyncWriteRefusedException(IllegalStateException("403 42501"))),
        )

        val failure = syncer(backend, companion).syncNow().exceptionOrNull()

        assertIs<SyncWriteRefusedException>(failure, "a forged row gets the same 403 and is not the paywall")
        assertEquals(0, companion.pulls)
    }

    @Test
    fun `a grant that lapses during the pull is not reported as synced`() = runTest {
        val backend = GateSyncBackend(grantedUntil = live)
        val companion = ScriptedCompanionBackend(onPull = { backend.grantedUntil = lapsed })

        assertEquals(CompanionSyncResult.NotEntitled, syncer(backend, companion).syncNow().getOrThrow())
    }

    @Test
    fun `a paid cycle pushes what it has, pulls, and clears the rows it sent`() = runTest {
        dirtyLink()
        val backend = GateSyncBackend(grantedUntil = live)
        val companion = ScriptedCompanionBackend()

        assertEquals(CompanionSyncResult.Synced, syncer(backend, companion).syncNow().getOrThrow())
        assertEquals(1, companion.pushes)
        assertEquals(1, companion.pulls)
        assertTrue(database.companionLinkQueries.selectDirty().awaitAsList().isEmpty())
    }
}
