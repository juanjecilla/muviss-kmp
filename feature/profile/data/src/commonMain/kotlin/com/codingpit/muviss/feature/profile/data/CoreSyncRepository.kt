package com.codingpit.muviss.feature.profile.data

import com.codingpit.muviss.core.sync.SyncAvailability
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.core.sync.SyncOutcome
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import com.codingpit.muviss.feature.profile.domain.SyncOutcomeSummary
import com.codingpit.muviss.feature.profile.domain.SyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [SyncRepository] over `:core:sync`'s [SyncBackend]/[SyncEngine] — this is
 * the only place in the codebase where a feature depends on `:core:sync`
 * directly (allowed: it's core infra, like `:core:database`, not a peer
 * feature — ADR 0004's "peers only via `:api`" rule doesn't apply to core
 * modules). Everything above this class (the profile ViewModel) only ever
 * sees [SyncRepository]'s domain-shaped types.
 */
class CoreSyncRepository(
    private val availability: SyncAvailability,
    private val backend: SyncBackend,
    private val engine: SyncEngine,
) : SyncRepository {

    override val isAvailable: Boolean get() = availability.isConfigured()

    override fun observeAccount(): Flow<SyncAccountState> = backend.session.map { session ->
        when {
            !isAvailable -> SyncAccountState.Unavailable
            session == null -> SyncAccountState.SignedOut
            else -> SyncAccountState.SignedIn(session.email)
        }
    }

    override fun observeLastSyncedAt(): Flow<Long?> = engine.observeLastSyncedAt()

    override suspend fun requestSignInCode(email: String): Result<Unit> = backend.requestEmailOtp(email)

    override suspend fun verifySignInCode(email: String, code: String): Result<Unit> = backend.verifyEmailOtp(email, code).map { }

    override suspend fun signOut() = backend.signOut()

    override suspend fun syncNow(): SyncOutcomeSummary {
        if (!isAvailable) return SyncOutcomeSummary.Unavailable
        return when (val outcome = engine.syncNow()) {
            SyncOutcome.NotSignedIn -> SyncOutcomeSummary.NotSignedIn
            is SyncOutcome.Success -> SyncOutcomeSummary.Success(outcome.syncedAtEpochMs)
            is SyncOutcome.Failed -> SyncOutcomeSummary.Failed(outcome.message)
        }
    }
}
