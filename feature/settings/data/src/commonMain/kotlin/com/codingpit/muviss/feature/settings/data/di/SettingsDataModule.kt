package com.codingpit.muviss.feature.settings.data.di

import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.settings.data.DefaultSettingsApi
import org.koin.core.module.Module
import org.koin.dsl.module

val settingsDataModule: Module = module {
    single<SettingsApi> { DefaultSettingsApi() }
}
