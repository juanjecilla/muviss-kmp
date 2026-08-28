package com.codingpit.muviss.feature.collection.data.di

import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.ListsApi
import com.codingpit.muviss.feature.collection.data.DefaultCollectionApi
import com.codingpit.muviss.feature.collection.data.DefaultListsApi
import com.codingpit.muviss.feature.collection.data.RegistryMediaSnapshotSource
import com.codingpit.muviss.feature.collection.data.SqlDelightCollectionRepository
import com.codingpit.muviss.feature.collection.data.SqlDelightListsRepository
import com.codingpit.muviss.feature.collection.domain.AddToCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.CollectionRefreshThrottle
import com.codingpit.muviss.feature.collection.domain.CollectionRepository
import com.codingpit.muviss.feature.collection.domain.CollectionToggles
import com.codingpit.muviss.feature.collection.domain.ListsRepository
import com.codingpit.muviss.feature.collection.domain.ListsUseCases
import com.codingpit.muviss.feature.collection.domain.MediaSnapshotSource
import com.codingpit.muviss.feature.collection.domain.ObserveCollectionEntryUseCase
import com.codingpit.muviss.feature.collection.domain.ObserveCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.RefreshAndFindNewEpisodesUseCase
import com.codingpit.muviss.feature.collection.domain.RefreshCollectionSnapshotsUseCase
import com.codingpit.muviss.feature.collection.domain.RemoveFromCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.SetNoteUseCase
import com.codingpit.muviss.feature.collection.domain.SetRatingUseCase
import com.codingpit.muviss.feature.collection.domain.ToggleFavoriteUseCase
import com.codingpit.muviss.feature.collection.domain.ToggleNotificationsMutedUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/** Repository + use-case + [CollectionApi]/[ListsApi] bindings for the collection feature. */
val collectionDataModule: Module = module {
    single { get<MuvissDatabase>().collectionEntryQueries }
    single<CollectionRepository> { SqlDelightCollectionRepository(get(), get(), get(), get()) }
    single<MediaSnapshotSource> { RegistryMediaSnapshotSource(get(), get()) }
    single<CollectionApi> { DefaultCollectionApi(get(), get(), get(), get(), get(), get()) }

    factory { ObserveCollectionUseCase(get()) }
    factory { ObserveCollectionEntryUseCase(get()) }
    factory { AddToCollectionUseCase(get()) }
    factory { RemoveFromCollectionUseCase(get()) }
    factory { ToggleFavoriteUseCase(get()) }
    factory { ToggleNotificationsMutedUseCase(get()) }
    factory { SetRatingUseCase(get()) }
    factory { SetNoteUseCase(get()) }
    factory { CollectionToggles(get(), get(), get(), get()) }
    factory { RefreshCollectionSnapshotsUseCase(get(), get()) }
    // A `single`: the throttle is only useful if every Collection back-stack entry shares one.
    single { CollectionRefreshThrottle(get()) }
    factory { RefreshAndFindNewEpisodesUseCase(get(), get(), get()) }

    // Lists (EPIC 17) — see ListsApi's KDoc for why this is a sibling
    // binding rather than folded into CollectionApi/DefaultCollectionApi.
    single { get<MuvissDatabase>().mediaListQueries }
    single<ListsRepository> { SqlDelightListsRepository(get(), get(), get()) }
    factory { ListsUseCases(get()) }
    single<ListsApi> { DefaultListsApi(get()) }
}
