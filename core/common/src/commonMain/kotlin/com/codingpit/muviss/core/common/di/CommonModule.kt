package com.codingpit.muviss.core.common.di

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.DefaultAppDispatchers
import com.codingpit.muviss.core.common.SystemClock
import org.koin.core.module.Module
import org.koin.dsl.module

/** Shared, cross-cutting bindings used by every feature. */
val commonModule: Module = module {
    single<AppDispatchers> { DefaultAppDispatchers() }
    single<AppClock> { SystemClock() }
}
