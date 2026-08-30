package com.codingpit.muviss.core.billing.di

import com.codingpit.muviss.core.billing.AlwaysEntitledProvider
import com.codingpit.muviss.core.billing.EntitlementProvider
import com.codingpit.muviss.core.billing.MuvissBuildConfig
import com.codingpit.muviss.core.billing.NoEntitlementProvider
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Binds the [EntitlementProvider]. Today the choice is between the two
 * no-store implementations; a `RevenueCatEntitlementProvider` in
 * `androidMain`/`iosMain` slots in here later without any caller changing
 * (ADR 0012's Phase D).
 */
val billingModule: Module = module {
    single<EntitlementProvider> {
        if (MuvissBuildConfig.SYNC_ENTITLEMENT_OVERRIDE) AlwaysEntitledProvider() else NoEntitlementProvider()
    }
}
