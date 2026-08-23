package com.codingpit.muviss.core.common.di

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.DefaultAppDispatchers
import com.codingpit.muviss.core.common.SystemClock
import com.codingpit.muviss.core.common.analytics.AnalyticsTracker
import com.codingpit.muviss.core.common.analytics.NoOpAnalyticsTracker
import org.koin.core.module.Module
import org.koin.dsl.module

/** Shared, cross-cutting bindings used by every feature. */
val commonModule: Module = module {
    single<AppDispatchers> { DefaultAppDispatchers() }
    single<AppClock> { SystemClock() }
    single { AppVersion.current }
    // No vendor is wired; see AnalyticsTracker's KDoc. `FeatureFlags` is bound
    // by feature/settings/data, which owns the appSettings row it reads.
    single<AnalyticsTracker> { NoOpAnalyticsTracker }
}
