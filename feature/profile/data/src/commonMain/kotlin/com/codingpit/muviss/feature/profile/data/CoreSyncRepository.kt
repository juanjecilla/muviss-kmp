package com.codingpit.muviss.feature.profile.data

import com.codingpit.muviss.core.billing.EntitlementProvider
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.sync.OAuthProvider
import com.codingpit.muviss.core.sync.OAuthRedirectTarget
import com.codingpit.muviss.core.sync.SignInFeedback
import com.codingpit.muviss.core.sync.SyncAvailability
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncCoordinator
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.core.sync.SyncFailureReason
import com.codingpit.muviss.core.sync.SyncOutcome
import com.codingpit.muviss.feature.profile.domain.AutomaticSyncMode
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import com.codingpit.muviss.feature.profile.domain.SyncFailureKind
import com.codingpit.muviss.feature.profile.domain.SyncOutcomeSummary
import com.codingpit.muviss.feature.profile.domain.SyncProvider
import com.codingpit.muviss.feature.profile.domain.SyncRepository
import com.codingpit.muviss.feature.profile.domain.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [SyncRepository] over `:core:sync`'s [SyncBackend]/[SyncEngine] — this is
 * the only place in the codebase where a feature depends on `:core:sync`
 * directly (allowed: it's core infra, like `:core:database`, not a peer
 * feature — ADR 0004's "peers only via `:api`" rule doesn't apply to core
 * modules). Everything above this class (the profile ViewModel) only ever
 * sees [SyncRepository]'s domain-shaped types.
 */
@Suppress("LongParameterList") // the one place `:core:sync`, `:core:billing` and the flag registry meet; each is a distinct seam
class CoreSyncRepository(
    private val availability: SyncAvailability,
    private val backend: SyncBackend,
    private val engine: SyncEngine,
    private val entitlements: EntitlementProvider,
    private val signInFeedback: SignInFeedback,
    private val redirectTarget: OAuthRedirectTarget,
    private val flags: FeatureFlags,
    private val coordinator: SyncCoordinator,
    override val automaticSyncMode: AutomaticSyncMode = platformAutomaticSyncMode(),
) : SyncRepository {

    override val isAvailable: Boolean get() = availability.isConfigured()

    override val isBackgroundAvailable: Boolean get() = availability.isBackgroundAvailable()

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
        engine.observeStatus(),
    ) { session, entitlement, status ->
        when {
            !isAvailable -> SyncAccountState.Unavailable

            !entitlement.isEntitled -> SyncAccountState.Locked(session?.email)

            // A dead session is a sign-out nobody chose. The failure it left
            // behind is what tells the two apart; an explicit sign-out clears it.
            session == null && status.lastFailure == SyncFailureReason.Unauthorised -> SyncAccountState.SessionExpired

            session == null -> SyncAccountState.SignedOut

            else -> SyncAccountState.SignedIn(session.email)
        }
    }.distinctUntilChanged()

    override fun observeAutomaticSync(): Flow<Boolean> = flags.syncAutomatically

    /**
     * The coordinator already follows the flag on its own; starting it here as
     * well is what makes a first toggle work in a host that never called
     * [SyncCoordinator.start]. Turning it on also asks for a pull straight away,
     * because the coordinator only wakes for local writes and the person has
     * just said they want the other devices' changes.
     */
    override suspend fun setAutomaticSync(enabled: Boolean) {
        flags.setSyncAutomatically(enabled)
        if (enabled) {
            coordinator.start()
            coordinator.onForeground()
        }
    }

    override fun observeSyncStatus(): Flow<SyncStatus> = combine(engine.observeStatus(), backend.session) { snapshot, session ->
        SyncStatus(
            lastSyncedAtEpochMs = snapshot.lastSyncedAtEpochMs,
            pendingChanges = snapshot.pendingChanges,
            lastFailure = snapshot.lastFailure?.toDomain(),
            accountChanged = snapshot.ownerAccountId != null && session != null && snapshot.ownerAccountId != session.userId,
        )
    }.distinctUntilChanged()

    override fun observeLastSyncedAt(): Flow<Long?> = engine.observeLastSyncedAt()

    /**
     * The redirect comes from [OAuthRedirectTarget] rather than a constant
     * because desktop's is a loopback URL whose port is not known until a
     * socket is bound (ADR 0017). Reserving it is therefore a step that can
     * fail, and a failure here has to release it again: an attempt that never
     * reached the browser must not leave a port listening for a code that is
     * never coming.
     */
    override suspend fun beginSignIn(provider: SyncProvider): Result<String> = runCatching { redirectTarget.reserve() }
        .mapCatching { redirectUri -> backend.beginOAuth(provider = provider.toBackend(), redirectUri = redirectUri).getOrThrow() }
        .onFailure { redirectTarget.release() }

    override suspend fun completeSignIn(authCode: String): Result<Unit> = backend.completeOAuth(authCode).map { }

    override fun observeSignInFailure(): Flow<String?> = signInFeedback.lastFailure

    override fun signInFailureShown() = signInFeedback.consume()

    private fun SyncProvider.toBackend(): OAuthProvider = when (this) {
        SyncProvider.GITHUB -> OAuthProvider.GITHUB
        SyncProvider.GOOGLE -> OAuthProvider.GOOGLE
    }

    override suspend fun signOut() {
        backend.signOut()
        // Leaving on purpose must not read as "session expired" afterwards.
        engine.forgetLastFailure()
    }

    override suspend fun syncNow(): SyncOutcomeSummary = if (isAvailable) summarise(engine.syncNow()) else SyncOutcomeSummary.Unavailable

    override suspend fun resyncEverything(): SyncOutcomeSummary = if (isAvailable) summarise(engine.resyncEverything()) else SyncOutcomeSummary.Unavailable

    private fun SyncFailureReason.toDomain(): SyncFailureKind = when (this) {
        SyncFailureReason.Offline -> SyncFailureKind.Offline
        SyncFailureReason.Unauthorised -> SyncFailureKind.Unauthorised
        SyncFailureReason.Server -> SyncFailureKind.Server
        SyncFailureReason.Unknown -> SyncFailureKind.Unknown
    }

    private fun summarise(outcome: SyncOutcome): SyncOutcomeSummary = when (outcome) {
        SyncOutcome.NotSignedIn -> SyncOutcomeSummary.NotSignedIn

        // Only an automatic trigger is ever refused for being switched off,
        // and none of them comes through here.
        SyncOutcome.Disabled -> SyncOutcomeSummary.Unavailable

        SyncOutcome.NotEntitled -> SyncOutcomeSummary.NotEntitled

        is SyncOutcome.Success -> SyncOutcomeSummary.Success(outcome.syncedAtEpochMs)

        is SyncOutcome.Failed -> SyncOutcomeSummary.Failed(outcome.reason.toDomain())

        // EPIC 39 built the mechanism (`SyncEngine.resolveAccountChange`);
        // the confirmation that decides between discarding this device's
        // library and merging it belongs to the account work (EPIC 32's
        // ADR 0019), so until then this is surfaced clearly and left alone
        // rather than silently syncing one person's library into another's.
        is SyncOutcome.AccountChanged -> SyncOutcomeSummary.AccountChanged
    }
}
