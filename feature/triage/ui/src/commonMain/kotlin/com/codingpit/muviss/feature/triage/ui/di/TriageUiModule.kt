package com.codingpit.muviss.feature.triage.ui.di

import com.codingpit.muviss.core.common.analytics.AnalyticsTracker
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.triage.domain.LoadDeckGenresUseCase
import com.codingpit.muviss.feature.triage.domain.SeededDeckFeed
import com.codingpit.muviss.feature.triage.domain.TriageActions
import com.codingpit.muviss.feature.triage.domain.TriageOnboarding
import com.codingpit.muviss.feature.triage.ui.SkippedViewModel
import com.codingpit.muviss.feature.triage.ui.SnoozedViewModel
import com.codingpit.muviss.feature.triage.ui.TriageViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Names the seeded deck's ViewModel (#554), so it is never confused with the catalogue deck's, which is unqualified. */
val SEEDED_DECK_QUALIFIER: Qualifier = named("triage-seeded-deck")

val triageUiModule: Module = module {
    viewModelOf(::TriageViewModel)
    viewModelOf(::SkippedViewModel)
    viewModelOf(::SnoozedViewModel)

    // The same screen model over the seeded feed (#554). It is handed the catalogue's genre loader, which it never
    // asks: a seeded deck has no genre chips.
    viewModel(qualifier = SEEDED_DECK_QUALIFIER) {
        TriageViewModel(
            loadDeck = get<SeededDeckFeed>(),
            loadGenres = get<LoadDeckGenresUseCase>(),
            actions = get<TriageActions>(),
            onboarding = get<TriageOnboarding>(),
            featureFlags = get<FeatureFlags>(),
            analytics = get<AnalyticsTracker>(),
            settings = get<SettingsApi>(),
        )
    }
}
