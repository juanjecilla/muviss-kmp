package com.codingpit.muviss.feature.nextpick.data.di

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.civilDateOf
import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
import com.codingpit.muviss.core.common.localTodayEpochDay
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.WatchProviderFill
import com.codingpit.muviss.core.network.WatchProviderLookup
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.nextpick.data.AnchorCandidates
import com.codingpit.muviss.feature.nextpick.data.AppSettingsCompletionPromptStore
import com.codingpit.muviss.feature.nextpick.data.CollectionBacklogCandidates
import com.codingpit.muviss.feature.nextpick.data.CollectionHistoryReader
import com.codingpit.muviss.feature.nextpick.data.CombinedCandidates
import com.codingpit.muviss.feature.nextpick.data.CrashPickRankerReporter
import com.codingpit.muviss.feature.nextpick.data.DefaultNextPickActions
import com.codingpit.muviss.feature.nextpick.data.DiscoverSource
import com.codingpit.muviss.feature.nextpick.data.LibraryGenreIds
import com.codingpit.muviss.feature.nextpick.data.NextPickContextReader
import com.codingpit.muviss.feature.nextpick.data.OwnServicesCandidates
import com.codingpit.muviss.feature.nextpick.data.SeedCandidates
import com.codingpit.muviss.feature.nextpick.data.SettingsMuting
import com.codingpit.muviss.feature.nextpick.data.TasteHistoryReader
import com.codingpit.muviss.feature.nextpick.data.TriageExclusions
import com.codingpit.muviss.feature.nextpick.domain.CompletionPromptStore
import com.codingpit.muviss.feature.nextpick.domain.NextPickActions
import com.codingpit.muviss.feature.nextpick.domain.PickContextSource
import com.codingpit.muviss.feature.nextpick.domain.PickEngine
import com.codingpit.muviss.feature.nextpick.domain.PickRankerReporter
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.settings.api.TasteProfileApi
import com.codingpit.muviss.feature.triage.api.TriageApi
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Next Pick's engine, its sources and its actions (EPIC 51, #400). Peers are resolved per call with
 * `{ get() }`, the same cycle-avoidance rule as progress and triage: nothing here may make a peer's
 * construction depend on this module.
 */
val nextPickDataModule: Module = module {
    single<PickRankerReporter> { CrashPickRankerReporter() }
    single<CompletionPromptStore> { AppSettingsCompletionPromptStore(get<MuvissDatabase>().appSettingsQueries, get(), get()) }

    single {
        val collection: () -> CollectionApi = { get() }
        val triage: () -> TriageApi = { get() }
        val settings: () -> SettingsApi = { get() }
        val clock: AppClock = get()
        val connectivity: ConnectivityMonitor = get()
        val genreIds: LibraryGenreIds = get()
        val thisYear = { civilDateOf(clock.localTodayEpochDay()).year }
        val metadata: MetadataProvider = get()
        val taste: () -> TasteProfileApi = { get() }
        val region: () -> String = { get<MetadataLocale>().region }
        val lookup: () -> WatchProviderLookup = { get() }
        val fill: () -> WatchProviderFill = { get() }
        val muting = SettingsMuting(settings)
        // Order is not priority: PickEngine merges one copy per title by source (backlog, then anchor, then discover).
        PickEngine(
            sources = listOf(
                // "Only my services" (#740) narrows the sources TMDB does not filter by provider; discover does it itself.
                // One pool, so one request budget per pick (#762), not one per source.
                OwnServicesCandidates(
                    inner = CombinedCandidates(
                        listOf(
                            CollectionBacklogCandidates(collection, genreIds, clock),
                            AnchorCandidates(metadata, collection),
                            SeedCandidates(metadata, collection),
                        ),
                    ),
                    taste = taste,
                    lookup = lookup,
                    fill = fill,
                    region = region,
                ),
                DiscoverSource(metadata, collection, thisYear, muting, taste, region),
            ),
            exclusions = TriageExclusions(triage),
            history = TasteHistoryReader(CollectionHistoryReader(clock, collection, connectivity, genreIds), taste),
            muting = muting,
            reporter = get(),
        )
    }

    // Library titles store genre names; this maps them to TMDB ids (#547). One instance, so the memo is shared.
    single {
        LibraryGenreIds(
            liveCatalogue = { type -> get<MetadataProvider>().genres(type) },
            language = { get<MetadataLocale>().language },
        )
    }

    single<PickContextSource> { NextPickContextReader(get(), { get<CollectionApi>() }, get(), { get<TasteProfileApi>() }) }

    single<NextPickActions> {
        DefaultNextPickActions({ get<CollectionApi>() }, { get<TriageApi>() }, get(), get(), get())
    }
}
