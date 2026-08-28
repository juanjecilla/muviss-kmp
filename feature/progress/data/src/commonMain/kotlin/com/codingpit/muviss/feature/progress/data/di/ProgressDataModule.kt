package com.codingpit.muviss.feature.progress.data.di

import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.data.DefaultProgressApi
import com.codingpit.muviss.feature.progress.data.ProgressPlayObservers
import com.codingpit.muviss.feature.progress.data.RegistryEpisodeCatalogSource
import com.codingpit.muviss.feature.progress.data.SqlDelightProgressRepository
import com.codingpit.muviss.feature.progress.domain.ClearPlaysUseCase
import com.codingpit.muviss.feature.progress.domain.ClearProgressUseCase
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogSource
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.feature.progress.domain.MarkAllAiredSeenUseCase
import com.codingpit.muviss.feature.progress.domain.MarkPreviousSeenUseCase
import com.codingpit.muviss.feature.progress.domain.MarkSeasonAiredSeenUseCase
import com.codingpit.muviss.feature.progress.domain.MarkShowAiredSeenUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveEpisodeProgressUseCase
import com.codingpit.muviss.feature.progress.domain.ObservePlayCountsUseCase
import com.codingpit.muviss.feature.progress.domain.ObservePlaysUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenActivityEpochDaysUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenCountUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenEpisodesUseCase
import com.codingpit.muviss.feature.progress.domain.ProgressBulkMutations
import com.codingpit.muviss.feature.progress.domain.ProgressMutations
import com.codingpit.muviss.feature.progress.domain.ProgressPlayMutations
import com.codingpit.muviss.feature.progress.domain.ProgressRepository
import com.codingpit.muviss.feature.progress.domain.RecordPlayUseCase
import com.codingpit.muviss.feature.progress.domain.RemoveLatestPlayUseCase
import com.codingpit.muviss.feature.progress.domain.SetMovieWatchedUseCase
import com.codingpit.muviss.feature.progress.domain.ToggleEpisodeSeenUseCase
import com.codingpit.muviss.feature.progress.domain.UndoBulkMarkUseCase
import com.codingpit.muviss.feature.progress.domain.UnmarkSeasonsUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/** Repository + use-case + [ProgressApi] bindings for the progress feature. */
val progressDataModule: Module = module {
    single { get<MuvissDatabase>().episodeProgressQueries }
    single { get<MuvissDatabase>().episodePlayQueries }
    single<ProgressRepository> { SqlDelightProgressRepository(get(), get(), get(), get()) }
    single<EpisodeCatalogSource> { RegistryEpisodeCatalogSource(get(), get()) }
    single<ProgressApi> { DefaultProgressApi(get(), get(), get(), get()) }

    factory { ObserveEpisodeProgressUseCase(get()) }
    factory { ObserveSeenEpisodesUseCase(get()) }
    factory { ObserveSeenCountUseCase(get()) }
    factory { ObserveSeenActivityEpochDaysUseCase(get()) }
    factory { ToggleEpisodeSeenUseCase(get()) }
    factory { MarkSeasonAiredSeenUseCase(get()) }
    factory { MarkShowAiredSeenUseCase(get()) }
    factory { UnmarkSeasonsUseCase(get()) }
    factory { UndoBulkMarkUseCase(get()) }
    factory { RecordPlayUseCase(get()) }
    factory { RemoveLatestPlayUseCase(get()) }
    factory { ClearPlaysUseCase(get()) }
    factory { ObservePlayCountsUseCase(get()) }
    factory { ObservePlaysUseCase(get()) }
    factory { ProgressPlayObservers(get(), get()) }
    factory { MarkPreviousSeenUseCase(get()) }
    factory { MarkAllAiredSeenUseCase(get()) }
    factory { ClearProgressUseCase(get()) }
    factory { ProgressPlayMutations(get(), get(), get()) }
    factory { ProgressBulkMutations(get(), get(), get(), get(), get(), get()) }
    factory { ProgressMutations(get(), get(), get(), get(), get()) }
    factory { SetMovieWatchedUseCase(get()) }
    factory { FetchEpisodeCatalogUseCase(get()) }
    single { EpisodeCatalogCache(get()) }
}
