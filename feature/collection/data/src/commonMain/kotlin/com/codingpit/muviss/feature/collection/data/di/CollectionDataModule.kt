package com.codingpit.muviss.feature.collection.data.di

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.data.DefaultCollectionApi
import org.koin.core.module.Module
import org.koin.dsl.module

val collectionDataModule: Module = module {
    single<CollectionApi> { DefaultCollectionApi() }
}
