package com.codingpit.muviss.feature.nextpick.data.di

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.civilDateOf
import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.nextpick.data.AppSettingsCompletionPromptStore
import com.codingpit.muviss.feature.nextpick.data.CollectionBacklogCandidates
import com.codingpit.muviss.feature.nextpick.data.CollectionHistoryReader
import com.codingpit.muviss.feature.nextpick.data.DefaultNextPickActions
import com.codingpit.muviss.feature.nextpick.data.DiscoverSource
import com.codingpit.muviss.feature.nextpick.data.LibraryGenreIds
import com.codingpit.muviss.feature.nextpick.data.NextPickContextReader
import com.codingpit.muviss.feature.nextpick.data.SettingsMuting
import com.codingpit.muviss.feature.nextpick.data.TriageExclusions
import com.codingpit.muviss.feature.nextpick.domain.CompletionPromptStore
import com.codingpit.muviss.feature.nextpick.domain.DefaultNextPickSettings
import com.codingpit.muviss.feature.nextpick.domain.NextPickActions
import com.codingpit.muviss.feature.nextpick.domain.NextPickSettings
import com.codingpit.muviss.feature.nextpick.domain.PickContextSource
import com.codingpit.muviss.feature.nextpick.domain.PickEngine
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.triage.api.TriageApi
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Next Pick's engine, its sources and its actions (EPIC 51, #400). Peers are resolved per call with
 * `{ get() }`, the same cycle-avoidance rule as progress and triage: nothing here may make a peer's
 * construction depend on this module.
 */
val nextPickDataModule: Module = module {
    single<NextPickSettings> { DefaultNextPickSettings }
    single<CompletionPromptStore> { AppSettingsCompletionPromptStore(get<MuvissDatabase>().appSettingsQueries, get()) }

    single {
        val collection: () -> CollectionApi = { get() }
        val triage: () -> TriageApi = { get() }
        val settings: () -> SettingsApi = { get() }
        val clock: AppClock = get()
        val connectivity: ConnectivityMonitor = get()
        val genreIds: LibraryGenreIds = get()
        val thisYear = { civilDateOf(clock.nowEpochMs().floorDiv(MS_PER_DAY)).year }
        PickEngine(
            sources = listOf(
                CollectionBacklogCandidates(collection, genreIds),
                DiscoverSource(get<MetadataProvider>(), collection, thisYear),
            ),
            exclusions = TriageExclusions(triage),
            history = CollectionHistoryReader(clock, collection, connectivity, genreIds),
            muting = SettingsMuting(settings),
        )
    }

    // Library titles store genre names; this maps them to TMDB ids (#547). One instance, so the memo is shared.
    single {
        LibraryGenreIds(
            liveCatalogue = { type -> get<MetadataProvider>().genres(type) },
            language = { get<MetadataLocale>().language },
        )
    }

    single<PickContextSource> { NextPickContextReader(get(), { get<CollectionApi>() }, get()) }

    single<NextPickActions> {
        DefaultNextPickActions({ get<CollectionApi>() }, { get<TriageApi>() }, get(), get())
    }
}

private const val MS_PER_DAY = 86_400_000L
