package com.codingpit.muviss.di

import com.codingpit.muviss.core.billing.EntitlementProvider
import com.codingpit.muviss.core.sync.EntitlementGate
import kotlinx.coroutines.flow.first
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Joins `:core:billing` to `:core:sync` — the only place in the app where the
 * two meet.
 *
 * Neither module depends on the other, deliberately (ADR 0012). `:core:sync`
 * declares [EntitlementGate] as a bare `suspend () -> Boolean` so it never
 * learns what a store is, and `:core:billing` publishes an entitlement without
 * knowing what is gated on it. The app shell is where a wiring decision like
 * "sync is the paid feature" belongs, and swapping which features are paid is
 * an edit to this file alone.
 *
 * This module must come after `syncModule` in [appModules]: both bind
 * [EntitlementGate], and Koin's last binding wins. `syncModule`'s permissive
 * default is what keeps `:core:sync` usable — and testable — on its own.
 */
val billingSyncBridgeModule: Module = module {
    single<EntitlementGate> {
        val provider = get<EntitlementProvider>()
        EntitlementGate { provider.entitlement.first().isEntitled }
    }
}
