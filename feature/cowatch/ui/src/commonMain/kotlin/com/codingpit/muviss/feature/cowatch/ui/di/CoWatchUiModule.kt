package com.codingpit.muviss.feature.cowatch.ui.di

import com.codingpit.muviss.feature.cowatch.ui.CompanionsViewModel
import com.codingpit.muviss.feature.cowatch.ui.ShortlistViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val coWatchUiModule: Module = module {
    viewModelOf(::CompanionsViewModel)
    viewModelOf(::ShortlistViewModel)
}
