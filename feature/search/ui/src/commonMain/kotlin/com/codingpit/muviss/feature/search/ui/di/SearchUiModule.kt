package com.codingpit.muviss.feature.search.ui.di

import com.codingpit.muviss.feature.search.ui.DetailViewModel
import com.codingpit.muviss.feature.search.ui.SearchViewModel
import com.codingpit.muviss.models.MediaId
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val searchUiModule: Module = module {
    viewModelOf(::SearchViewModel)
    viewModel { (id: MediaId) -> DetailViewModel(id, get(), get()) }
}
