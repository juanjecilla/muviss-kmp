package com.codingpit.muviss.core.billing.di

import com.codingpit.muviss.core.billing.AlwaysEntitledProvider
import com.codingpit.muviss.core.billing.EntitlementProvider
import com.codingpit.muviss.core.billing.EntitlementSource
import com.codingpit.muviss.core.billing.MuvissBuildConfig
import com.codingpit.muviss.core.billing.NoEntitlementProvider
import com.codingpit.muviss.core.billing.SupabaseEntitlementProvider
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Binds the [EntitlementProvider].
 *
 * `SYNC_ENTITLEMENT_OVERRIDE` wins (ADR 0018): a developer build is granted it
 * outright. Otherwise the server's own record decides
 * ([SupabaseEntitlementProvider], ADR 0019), read through the
 * [EntitlementSource] the app shell binds over the sync backend. With no source
 * in the graph — this module on its own, outside the app — nobody is
 * entitled: fail closed, as [NoEntitlementProvider] always did. A
 * `RevenueCatEntitlementProvider` in `androidMain`/`iosMain` slots in here
 * later without any caller changing.
 */
val billingModule: Module = module {
    single<EntitlementProvider> {
        when {
            MuvissBuildConfig.SYNC_ENTITLEMENT_OVERRIDE -> AlwaysEntitledProvider()
            else -> getOrNull<EntitlementSource>()?.let { source -> SupabaseEntitlementProvider(source, get()) } ?: NoEntitlementProvider()
        }
    }
}
