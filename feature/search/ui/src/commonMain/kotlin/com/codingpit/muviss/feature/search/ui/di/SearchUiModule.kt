package com.codingpit.muviss.feature.search.ui.di

import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.tickAnimationsActive
import com.codingpit.muviss.feature.search.ui.AddToListViewModel
import com.codingpit.muviss.feature.search.ui.DetailPeers
import com.codingpit.muviss.feature.search.ui.DetailViewModel
import com.codingpit.muviss.feature.search.ui.DiscoverIntroViewModel
import com.codingpit.muviss.feature.search.ui.EpisodeDetailViewModel
import com.codingpit.muviss.feature.search.ui.NotificationNudgeViewModel
import com.codingpit.muviss.feature.search.ui.SearchViewModel
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val searchUiModule: Module = module {
    // The motion switch is a flow, which the constructor-reflecting DSL cannot supply on its own.
    viewModel { SearchViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get<FeatureFlags>().animationsEnabled) }
    viewModelOf(::DiscoverIntroViewModel)
    viewModel { (id: MediaId) -> DetailViewModel(id, get(), DetailPeers(get(), get(), get(), get()), get(), get(), get(), get(), get<FeatureFlags>().tickAnimationsActive) }
    viewModel { (id: MediaId) -> AddToListViewModel(id, get()) }
    viewModel { (id: MediaId) -> NotificationNudgeViewModel(id, get(), get()) }
    viewModel { (id: EpisodeId) -> EpisodeDetailViewModel(id, get(), get(), get()) }
}
