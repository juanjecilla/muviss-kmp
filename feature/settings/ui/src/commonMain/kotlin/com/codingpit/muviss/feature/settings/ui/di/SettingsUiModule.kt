package com.codingpit.muviss.feature.settings.ui.di

import com.codingpit.muviss.feature.settings.ui.SettingsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val settingsUiModule: Module = module {
    viewModelOf(::SettingsViewModel)
}
