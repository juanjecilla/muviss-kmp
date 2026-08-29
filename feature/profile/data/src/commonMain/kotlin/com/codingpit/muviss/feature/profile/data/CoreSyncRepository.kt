package com.codingpit.muviss.feature.profile.data

import com.codingpit.muviss.core.billing.EntitlementProvider
import com.codingpit.muviss.core.sync.OAUTH_REDIRECT_URI
import com.codingpit.muviss.core.sync.OAuthProvider
import com.codingpit.muviss.core.sync.SyncAvailability
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.core.sync.SyncOutcome
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import com.codingpit.muviss.feature.profile.domain.SyncOutcomeSummary
import com.codingpit.muviss.feature.profile.domain.SyncProvider
import com.codingpit.muviss.feature.profile.domain.SyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    private val entitlements: EntitlementProvider,
) : SyncRepository {

    override val isAvailable: Boolean get() = availability.isConfigured()

    /**
     * Combined rather than read once, because an entitlement changes while the
     * app is open — a purchase completes, a subscription lapses, a refund
     * lands — and the sync section has to follow it without a restart.
     *
     * Locked outranks the session states on purpose: a paywall is what an
     * unentitled user needs to see whether or not they happen to be signed in,
     * and [SyncAccountState.Locked] carries the email so signing out stays
     * reachable from behind it.
     */
    override fun observeAccount(): Flow<SyncAccountState> = combine(
        backend.session,
        entitlements.entitlement,
    ) { session, entitlement ->
        when {
            !isAvailable -> SyncAccountState.Unavailable
            !entitlement.isEntitled -> SyncAccountState.Locked(session?.email)
            session == null -> SyncAccountState.SignedOut
            else -> SyncAccountState.SignedIn(session.email)
        }
    }

    override fun observeLastSyncedAt(): Flow<Long?> = engine.observeLastSyncedAt()

    override suspend fun beginSignIn(provider: SyncProvider): Result<String> = backend.beginOAuth(provider = provider.toBackend(), redirectUri = OAUTH_REDIRECT_URI)

    override suspend fun completeSignIn(authCode: String): Result<Unit> = backend.completeOAuth(authCode).map { }

    private fun SyncProvider.toBackend(): OAuthProvider = when (this) {
        SyncProvider.GITHUB -> OAuthProvider.GITHUB
        SyncProvider.GOOGLE -> OAuthProvider.GOOGLE
    }

    override suspend fun signOut() = backend.signOut()

    override suspend fun syncNow(): SyncOutcomeSummary {
        if (!isAvailable) return SyncOutcomeSummary.Unavailable
        return when (val outcome = engine.syncNow()) {
            SyncOutcome.NotSignedIn -> SyncOutcomeSummary.NotSignedIn
            SyncOutcome.NotEntitled -> SyncOutcomeSummary.NotEntitled
            is SyncOutcome.Success -> SyncOutcomeSummary.Success(outcome.syncedAtEpochMs)
            is SyncOutcome.Failed -> SyncOutcomeSummary.Failed(outcome.message)
        }
    }
}
