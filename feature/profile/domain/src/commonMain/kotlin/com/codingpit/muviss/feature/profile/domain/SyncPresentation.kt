package com.codingpit.muviss.feature.profile.domain

/**
 * Why a sync failed, mirrored from `core.sync.SyncFailureReason` for the same
 * reason [SyncAccountState] mirrors the session: `feature/profile/ui` may not
 * depend on `:core:sync` (ADR 0004). The wording is [SyncCopy]'s; nothing here
 * carries an exception's text, because that is whatever a socket happened to say.
 */
enum class SyncFailureKind {
    Offline,
    Unauthorised,
    Server,
    Unknown,
}

/** How automatic sync actually happens on this platform. It changes what the switch can honestly promise. */
enum class AutomaticSyncMode {
    /** Android: WorkManager runs it with the app closed. */
    InBackground,

    /** iOS: a background refresh the system schedules; best effort. */
    WhenSystemAllows,

    /** Desktop and web: a timer that only ticks while the window or tab is open. */
    WhileOpen,
}

/** Everything the Profile status line needs from the sync engine, and nothing it would have to interpret. */
data class SyncStatus(
    val lastSyncedAtEpochMs: Long? = null,
    /** Local rows not yet sent. */
    val pendingChanges: Long = 0,
    /** Why the last attempt failed, or null if it did not. */
    val lastFailure: SyncFailureKind? = null,
    /** This device's library belongs to a different account than the one signed in (EPIC 39). Nothing syncs until that is resolved. */
    val accountChanged: Boolean = false,
)

/** The one thing worth saying under "Synced 3m ago", in priority order. */
sealed interface SyncStatusDetail {
    data object AccountChanged : SyncStatusDetail

    data class Failed(val kind: SyncFailureKind) : SyncStatusDetail

    data class Waiting(val count: Long) : SyncStatusDetail
}

/** An account mismatch outranks a failure, which outranks unsent changes: each explains the ones below it. */
fun syncStatusDetail(status: SyncStatus): SyncStatusDetail? = when {
    status.accountChanged -> SyncStatusDetail.AccountChanged
    status.lastFailure != null -> SyncStatusDetail.Failed(status.lastFailure)
    status.pendingChanges > 0 -> SyncStatusDetail.Waiting(status.pendingChanges)
    else -> null
}

/** All of the sync section's user-facing wording that depends on state, in one place so it is testable and never `e.message`. */
object SyncCopy {
    const val SESSION_EXPIRED = "Session expired, sign in again."

    fun failure(kind: SyncFailureKind): String = when (kind) {
        SyncFailureKind.Offline -> "you seem to be offline"
        SyncFailureKind.Unauthorised -> "your session was refused, sign in again"
        SyncFailureKind.Server -> "the sync service had a problem, try again later"
        SyncFailureKind.Unknown -> "something went wrong"
    }

    fun detail(detail: SyncStatusDetail): String = when (detail) {
        SyncStatusDetail.AccountChanged -> "This device's library belongs to a different account, so nothing is syncing."
        is SyncStatusDetail.Failed -> "Last sync failed: ${failure(detail.kind)}"
        is SyncStatusDetail.Waiting -> if (detail.count == 1L) "1 change waiting" else "${detail.count} changes waiting"
    }

    fun automaticSyncDescription(mode: AutomaticSyncMode): String = when (mode) {
        AutomaticSyncMode.InBackground -> "Keep this device in sync in the background, even when Muviss is closed."
        AutomaticSyncMode.WhenSystemAllows -> "Sync when the system allows it. iOS decides when, so it can be hours between runs."
        AutomaticSyncMode.WhileOpen -> "Sync while Muviss is open. Nothing runs once you close it."
    }
}
