package com.codingpit.muviss.feature.collection.data.di

import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.data.DefaultCollectionApi
import com.codingpit.muviss.feature.collection.data.RegistryMediaSnapshotSource
import com.codingpit.muviss.feature.collection.data.SqlDelightCollectionRepository
import com.codingpit.muviss.feature.collection.domain.AddToCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.CollectionRepository
import com.codingpit.muviss.feature.collection.domain.MediaSnapshotSource
import com.codingpit.muviss.feature.collection.domain.ObserveCollectionEntryUseCase
import com.codingpit.muviss.feature.collection.domain.ObserveCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.RefreshCollectionSnapshotsUseCase
import com.codingpit.muviss.feature.collection.domain.RemoveFromCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.ToggleFavoriteUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/** Repository + use-case + [CollectionApi] bindings for the collection feature. */
val collectionDataModule: Module = module {
    single { get<MuvissDatabase>().collectionEntryQueries }
    single<CollectionRepository> { SqlDelightCollectionRepository(get(), get(), get()) }
    single<MediaSnapshotSource> { RegistryMediaSnapshotSource(get(), get()) }
    single<CollectionApi> { DefaultCollectionApi(get(), get(), get(), get()) }

    factory { ObserveCollectionUseCase(get()) }
    factory { ObserveCollectionEntryUseCase(get()) }
    factory { AddToCollectionUseCase(get()) }
    factory { RemoveFromCollectionUseCase(get()) }
    factory { ToggleFavoriteUseCase(get()) }
    factory { RefreshCollectionSnapshotsUseCase(get(), get()) }
}
