package com.codingpit.muviss.feature.profile.domain

/**
 * Domain-level view of the sync feature's account state (EPIC 9) — a
 * deliberately thin mirror of `core.sync.SyncSession`/`SyncBackend`'s state,
 * so `:core:sync` (a Ktor/Supabase-facing infra module) never has to be
 * depended on by `feature/profile/ui` directly (ADR 0004: `ui -> domain <-
 * data`). See [SyncRepository].
 */
sealed interface SyncAccountState {
    /** No Supabase keys configured for this build — the UI hides the sync entry point entirely, same contract as a blank Sentry DSN (see CLAUDE.md). */
    data object Unavailable : SyncAccountState

    data object SignedOut : SyncAccountState

    /** [email] is always non-null here: this app's UI only ever completes sign-in via the email one-time-code flow (see `SyncRepository.verifySignInCode`) — `SyncBackend.signInAnonymously` exists for a future entry point but isn't wired into this screen yet. */
    data class SignedIn(val email: String?) : SyncAccountState
}

/** Domain-level mirror of `core.sync.SyncOutcome`, for the same reason as [SyncAccountState]. */
sealed interface SyncOutcomeSummary {
    data object Unavailable : SyncOutcomeSummary

    data object NotSignedIn : SyncOutcomeSummary

    data class Success(val syncedAtEpochMs: Long) : SyncOutcomeSummary

    data class Failed(val message: String) : SyncOutcomeSummary
}
