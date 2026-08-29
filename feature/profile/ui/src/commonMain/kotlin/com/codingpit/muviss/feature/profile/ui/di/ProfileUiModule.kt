package com.codingpit.muviss.feature.profile.ui.di

import com.codingpit.muviss.feature.profile.ui.ProfileViewModel
import com.codingpit.muviss.feature.profile.ui.RewatchViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val profileUiModule: Module = module {
    viewModelOf(::ProfileViewModel)
    viewModelOf(::RewatchViewModel)
}
