package com.codingpit.muviss.core.common.di

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.DefaultAppDispatchers
import com.codingpit.muviss.core.common.SystemClock
import com.codingpit.muviss.core.common.analytics.AnalyticsTracker
import com.codingpit.muviss.core.common.analytics.NoOpAnalyticsTracker
import com.codingpit.muviss.core.common.locale.DefaultSystemLocale
import com.codingpit.muviss.core.common.locale.SystemLocale
import com.codingpit.muviss.core.common.widget.AppWidgets
import com.codingpit.muviss.core.common.widget.WidgetRefresher
import org.koin.core.module.Module
import org.koin.dsl.module

/** Shared, cross-cutting bindings used by every feature. */
val commonModule: Module = module {
    single<AppDispatchers> { DefaultAppDispatchers() }
    single<AppClock> { SystemClock() }
    single { AppVersion.current }
    single<SystemLocale> { DefaultSystemLocale() }
    // No vendor is wired; see AnalyticsTracker's KDoc. `FeatureFlags` is bound
    // by feature/settings/data, which owns the appSettings row it reads.
    single<AnalyticsTracker> { NoOpAnalyticsTracker }
    // Home-screen widgets (EPIC 22). `AppWidgets` delegates per call to
    // whatever the platform host installed at startup, so this binding is
    // correct on every target whether or not one ever does.
    single<WidgetRefresher> { AppWidgets }
}
