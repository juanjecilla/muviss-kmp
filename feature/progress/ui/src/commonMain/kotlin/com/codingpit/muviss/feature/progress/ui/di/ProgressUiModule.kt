package com.codingpit.muviss.feature.progress.ui.di

import com.codingpit.muviss.feature.progress.ui.ProgressViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val progressUiModule: Module = module {
    viewModelOf(::ProgressViewModel)
}
