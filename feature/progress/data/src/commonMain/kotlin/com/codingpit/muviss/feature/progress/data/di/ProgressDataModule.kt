package com.codingpit.muviss.feature.progress.data.di

import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.data.DefaultProgressApi
import com.codingpit.muviss.feature.progress.data.RegistryEpisodeCatalogSource
import com.codingpit.muviss.feature.progress.data.SqlDelightProgressRepository
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogSource
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.feature.progress.domain.MarkPreviousSeenUseCase
import com.codingpit.muviss.feature.progress.domain.MarkSeasonSeenUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveEpisodeProgressUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenActivityEpochDaysUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenCountUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenEpisodesUseCase
import com.codingpit.muviss.feature.progress.domain.ProgressRepository
import com.codingpit.muviss.feature.progress.domain.SetMovieWatchedUseCase
import com.codingpit.muviss.feature.progress.domain.ToggleEpisodeSeenUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/** Repository + use-case + [ProgressApi] bindings for the progress feature. */
val progressDataModule: Module = module {
    single { get<MuvissDatabase>().episodeProgressQueries }
    single<ProgressRepository> { SqlDelightProgressRepository(get(), get(), get()) }
    single<EpisodeCatalogSource> { RegistryEpisodeCatalogSource(get(), get()) }
    single<ProgressApi> { DefaultProgressApi(get(), get(), get(), get(), get(), get()) }

    factory { ObserveEpisodeProgressUseCase(get()) }
    factory { ObserveSeenEpisodesUseCase(get()) }
    factory { ObserveSeenCountUseCase(get()) }
    factory { ObserveSeenActivityEpochDaysUseCase(get()) }
    factory { ToggleEpisodeSeenUseCase(get()) }
    factory { MarkSeasonSeenUseCase(get()) }
    factory { MarkPreviousSeenUseCase(get()) }
    factory { SetMovieWatchedUseCase(get()) }
    factory { FetchEpisodeCatalogUseCase(get()) }
    single { EpisodeCatalogCache(get()) }
}
