package com.codingpit.muviss.feature.profile.domain

/**
 * An identity provider offered on the sign-in button (ADR 0014).
 *
 * A domain-level mirror of `core.sync.OAuthProvider`, for the same reason as
 * [SyncAccountState]: `feature/profile/ui` reads this, and it must not have to
 * depend on `:core:sync` to do so (ADR 0004's `ui -> domain <- data`).
 */
enum class SyncProvider(val displayName: String) {
    GITHUB("GitHub"),
    GOOGLE("Google"),
}

/**
 * Domain-level view of the sync feature's account state (EPIC 9) — a
 * deliberately thin mirror of `core.sync.SyncSession`/`SyncBackend`'s state,
 * so `:core:sync` (a Ktor/Supabase-facing infra module) never has to be
 * depended on by `feature/profile/ui` directly (ADR 0004: `ui -> domain <-
 * data`). See [SyncRepository].
 */
sealed interface SyncAccountState {
    /** Sync is not in this build — `SYNC_ENABLED` unset or no Supabase keys. The UI hides the sync entry point entirely, same contract as a blank Sentry DSN (see CLAUDE.md). */
    data object Unavailable : SyncAccountState

    /**
     * Sync is in this build but the user has not paid for it (ADR 0018).
     *
     * The opposite presentation to [Unavailable], and the distinction is the
     * whole point of having two states: an unavailable feature renders
     * nothing, because offering something the build cannot do is a dead end;
     * a locked one renders the row and a way to buy it, because that is a
     * step the user can actually take.
     *
     * [email] is non-null when a lapsed subscriber is still signed in, so the
     * screen can keep offering sign-out rather than trapping them behind the
     * paywall with no way out of the account.
     */
    data class Locked(val email: String?) : SyncAccountState

    data object SignedOut : SyncAccountState

    /**
     * Signed out, but not by choice: the session died (the refresh token was
     * rejected) and the last sync said so. Rendered as [SignedOut] plus
     * [SyncCopy.SESSION_EXPIRED], so a silent stop cannot pass for "never
     * signed in". Cleared by signing in again or signing out on purpose.
     */
    data object SessionExpired : SyncAccountState

    /** [email] comes from the OAuth provider and is normally set, but is nullable because a provider can withhold it (a GitHub account with a private email does) — and `SyncBackend.signInAnonymously`, which exists for a future entry point but isn't wired into this screen, would have none at all. */
    data class SignedIn(val email: String?) : SyncAccountState
}

/** Domain-level mirror of `core.sync.SyncOutcome`, for the same reason as [SyncAccountState]. */
sealed interface SyncOutcomeSummary {
    data object Unavailable : SyncOutcomeSummary

    data object NotSignedIn : SyncOutcomeSummary

    /** The entitlement gate refused. Distinct from [NotSignedIn]: one needs a sign-in, the other a purchase. */
    data object NotEntitled : SyncOutcomeSummary

    data class Success(val syncedAtEpochMs: Long) : SyncOutcomeSummary

    /** The cycle ran and failed. [kind] is what the copy is keyed off; there is deliberately no message. */
    data class Failed(val kind: SyncFailureKind) : SyncOutcomeSummary

    /**
     * This device's library belongs to a different account than the one signed
     * in, so nothing moved. Nothing will until the person answers with
     * [SyncRepository.resolveAccountChange] — sending one person's library into
     * another's account is not something to do on their behalf.
     */
    data object AccountChanged : SyncOutcomeSummary
}

/**
 * The answer to [SyncOutcomeSummary.AccountChanged]: what happens to the
 * library on this device now that a different account is signed in (the
 * ADR 0019 decision recorded on #75). A domain mirror of
 * `core.sync.AccountChangeResolution`, for the same reason as [SyncAccountState].
 */
enum class AccountChangeChoice {
    /**
     * Delete this device's library and take the signed-in account's. The
     * default: nothing of the previous person's survives on the device, and
     * nothing of theirs is sent into someone else's account.
     */
    ReplaceWithAccountLibrary,

    /** Keep this device's library and send all of it to the signed-in account, alongside what the account already holds. */
    AddDeviceLibraryToAccount,
}
