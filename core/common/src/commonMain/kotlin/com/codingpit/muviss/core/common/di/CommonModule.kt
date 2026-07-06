package com.codingpit.muviss.core.common.di

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.DefaultAppDispatchers
import org.koin.core.module.Module
import org.koin.dsl.module

/** Shared, cross-cutting bindings used by every feature. */
val commonModule: Module = module {
    single<AppDispatchers> { DefaultAppDispatchers() }
}
