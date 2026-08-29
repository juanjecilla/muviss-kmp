package com.codingpit.muviss.core.sync

/**
 * Whether the user is currently entitled to sync at all.
 *
 * Sync is a paid feature, but `:core:sync` deliberately does not know what
 * "paid" means — this is the same seam-shaped reasoning ADR 0009 applied to
 * backends and ADR 0001 to metadata sources, and CONTEXT.md's `SyncEngine`
 * entry spells it out ("_Avoid_: backend, cloud — those are vendors behind
 * this seam"). A store, a receipt validator and a subscription lifecycle all
 * live behind this one boolean; `:core:billing` supplies the implementation
 * and `:app:shared` wires the two together, so `:core:sync` never depends on
 * `:core:billing` and neither one has to be rebuilt when the other's vendor
 * changes.
 *
 * The gate is checked inside [SyncEngine.syncNow] rather than in the profile
 * screen because the screen is not the only caller: `MuvissApp`'s
 * `AutoSyncOnForeground` calls [SyncEngine] directly on every `ON_START`, so a
 * UI-level check would leave the feature working for free on every app launch.
 *
 * Defaults to entitled where nothing binds a real one — a build with no
 * billing configured has already been gated out by `SyncAvailability` (see
 * `di/SyncModule.kt`), so the two gates are ANDed rather than each having to
 * re-state the other's rule.
 */
fun interface EntitlementGate {
    suspend fun isEntitled(): Boolean

    companion object {
        /** The binding used when no billing implementation is present. */
        val AlwaysEntitled: EntitlementGate = EntitlementGate { true }
    }
}
