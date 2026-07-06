package com.codingpit.muviss.feature.search.data.di

import com.codingpit.muviss.feature.search.data.TmdbSearchRepository
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.feature.search.domain.TrendingUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/** Repository + use-case bindings for the search feature. */
val searchDataModule: Module = module {
    single<SearchRepository> { TmdbSearchRepository(get(), get()) }
    factory { SearchMediaUseCase(get()) }
    factory { TrendingUseCase(get()) }
    factory { MediaDetailUseCase(get()) }
}
