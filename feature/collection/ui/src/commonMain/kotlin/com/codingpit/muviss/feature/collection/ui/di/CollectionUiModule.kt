package com.codingpit.muviss.feature.collection.ui.di

import com.codingpit.muviss.feature.collection.ui.CollectionViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val collectionUiModule: Module = module {
    viewModelOf(::CollectionViewModel)
}
