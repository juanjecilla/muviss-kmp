package com.codingpit.muviss.core.sync

/** What [AutoSyncPolicy] decided about one request for a sync cycle. */
enum class SyncDecision {
    /** Every gate that applies to this trigger passed. */
    Run,

    /** An automatic trigger, and the build or the user's switch says no. Nothing is attempted. */
    Disabled,

    /** No session. Skipped, not attempted. */
    NotSignedIn,

    /** A session, but the entitlement gate refused. Skipped, not attempted. */
    NotEntitled,
}

/**
 * The single rule for whether a sync cycle may start (EPIC 40, ADR 0021). Pure
 * on purpose: it is a truth table over five inputs, and it is enforced inside
 * [SyncEngine.syncNow] rather than by whoever calls it (ADR 0018 — a gate the
 * UI alone holds is a gate every other caller walks around).
 */
object AutoSyncPolicy {
    fun decide(
        trigger: SyncTrigger,
        backgroundAvailable: Boolean,
        switchOn: Boolean,
        signedIn: Boolean,
        entitled: Boolean,
    ): SyncDecision = when {
        // Ahead of the session checks on purpose: a switched-off automatic
        // trigger must not look like "sign in" or "buy" to anything reading
        // the outcome.
        trigger.isAutomatic && !(backgroundAvailable && switchOn) -> SyncDecision.Disabled

        !signedIn -> SyncDecision.NotSignedIn

        !entitled -> SyncDecision.NotEntitled

        else -> SyncDecision.Run
    }
}
