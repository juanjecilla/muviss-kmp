package com.codingpit.muviss.feature.cowatch.data.di
import com.codingpit.muviss.core.sync.secureRandomHex
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.ListsApi
import com.codingpit.muviss.feature.cowatch.api.CoWatchApi
import com.codingpit.muviss.feature.cowatch.data.CompanionSyncer
import com.codingpit.muviss.feature.cowatch.data.DefaultCoWatchApi
import com.codingpit.muviss.feature.cowatch.data.SqlDelightCoWatchRepository
import com.codingpit.muviss.feature.cowatch.domain.AcceptInviteUseCase
import com.codingpit.muviss.feature.cowatch.domain.CoWatchRepository
import com.codingpit.muviss.feature.cowatch.domain.CreateInviteUseCase
import com.codingpit.muviss.feature.cowatch.domain.ObserveActiveCompanionsUseCase
import com.codingpit.muviss.feature.cowatch.domain.ObserveCompanionsUseCase
import com.codingpit.muviss.feature.cowatch.domain.ObserveShortlistUseCase
import com.codingpit.muviss.feature.cowatch.domain.PublishWatchPoolUseCase
import org.koin.core.module.Module
import org.koin.dsl.module
/**
 * Co-watch's Koin module (EPIC 41).
 *
 * `CollectionApi` and `ListsApi` are injected as **providers**, not instances,
 * for the reason `WatchNextUseCase` already documents: collection's repository
 * reaches for a peer to derive status, so taking a peer eagerly here closes a
 * construction cycle Koin follows until the stack overflows — on the first
 * frame, before anything renders. `AppGraphTest` is the only test that catches
 * it; `Module.verify()` checks definitions without instantiating and would not.
 */
val coWatchDataModule: Module = module {
    single<CoWatchRepository> { SqlDelightCoWatchRepository(get(), get(), get(), get()) }
    single { CompanionSyncer(get(), get(), get(), get()) }
    factory { PublishWatchPoolUseCase({ get<CollectionApi>() }, { get<ListsApi>() }, get()) }
    factory { ObserveShortlistUseCase({ get<CollectionApi>() }, { get<ListsApi>() }, get()) }
    factory { ObserveCompanionsUseCase(get()) }
    factory { ObserveActiveCompanionsUseCase(get()) }
    factory { AcceptInviteUseCase(get()) }
    factory { CreateInviteUseCase(get()) { length -> secureRandomHex(length) } }
    single<CoWatchApi> { DefaultCoWatchApi(get(), get(), get(), get(), get(), get()) }
}
