package com.codingpit.muviss.feature.collection.ui.di

import com.codingpit.muviss.feature.collection.ui.CollectionViewModel
import com.codingpit.muviss.feature.collection.ui.ListContentsViewModel
import com.codingpit.muviss.feature.collection.ui.ListsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val collectionUiModule: Module = module {
    viewModelOf(::CollectionViewModel)
    viewModelOf(::ListsViewModel)
    viewModel { (listId: String) -> ListContentsViewModel(listId, get()) }
}
