package com.codingpit.muviss.feature.search.data.di

import com.codingpit.muviss.feature.search.data.AppSettingsSearchOnboarding
import com.codingpit.muviss.feature.search.data.TmdbSearchRepository
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.EpisodeDetailUseCase
import com.codingpit.muviss.feature.search.domain.GenresUseCase
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.MoreLikeThisUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.feature.search.domain.SearchOnboarding
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.feature.search.domain.SimilarMediaUseCase
import com.codingpit.muviss.feature.search.domain.TrendingUseCase
import com.codingpit.muviss.feature.search.domain.WatchProvidersUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/** Repository + use-case bindings for the search feature. */
val searchDataModule: Module = module {
    single<SearchRepository> { TmdbSearchRepository(get(), get(), get()) }
    single<SearchOnboarding> { AppSettingsSearchOnboarding(get(), get()) }
    factory { SearchMediaUseCase(get()) }
    factory { TrendingUseCase(get()) }
    factory { MediaDetailUseCase(get()) }
    factory { EpisodeDetailUseCase(get()) }
    factory { DiscoverMediaUseCase(get()) }
    factory { GenresUseCase(get()) }
    factory { WatchProvidersUseCase(get()) }
    factory { RecommendationsUseCase(get()) }
    factory { SimilarMediaUseCase(get()) }
    factory { MoreLikeThisUseCase(get(), get()) }
}
