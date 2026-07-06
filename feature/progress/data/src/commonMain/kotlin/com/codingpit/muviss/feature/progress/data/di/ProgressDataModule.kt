package com.codingpit.muviss.feature.progress.data.di

import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.data.DefaultProgressApi
import org.koin.core.module.Module
import org.koin.dsl.module

val progressDataModule: Module = module {
    single<ProgressApi> { DefaultProgressApi() }
}
