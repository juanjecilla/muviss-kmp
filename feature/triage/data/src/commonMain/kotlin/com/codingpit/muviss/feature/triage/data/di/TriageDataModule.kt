package com.codingpit.muviss.feature.triage.data.di

import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.triage.api.TriageApi
import com.codingpit.muviss.feature.triage.data.AppSettingsTriagePreferences
import com.codingpit.muviss.feature.triage.data.DefaultTriageApi
import com.codingpit.muviss.feature.triage.data.RegistryTriageDetailsSource
import com.codingpit.muviss.feature.triage.data.SqlDelightTriageDecisionRepository
import com.codingpit.muviss.feature.triage.data.SqlDelightTriageSnoozeRepository
import com.codingpit.muviss.feature.triage.data.TmdbDeckSource
import com.codingpit.muviss.feature.triage.domain.DeckLoader
import com.codingpit.muviss.feature.triage.domain.DeckSource
import com.codingpit.muviss.feature.triage.domain.LoadDeckGenresUseCase
import com.codingpit.muviss.feature.triage.domain.LoadDeckUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveDecidedIdsUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveDecisionUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveSkippedUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveSnoozeHintSeenUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveSnoozeUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveSnoozedIdsUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveSnoozedUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveTutorialSeenUseCase
import com.codingpit.muviss.feature.triage.domain.RecordDecisionUseCase
import com.codingpit.muviss.feature.triage.domain.RestoreDecisionUseCase
import com.codingpit.muviss.feature.triage.domain.RetryUnresolvedUseCase
import com.codingpit.muviss.feature.triage.domain.SetSnoozeHintSeenUseCase
import com.codingpit.muviss.feature.triage.domain.SetTutorialSeenUseCase
import com.codingpit.muviss.feature.triage.domain.SnoozeActions
import com.codingpit.muviss.feature.triage.domain.SnoozeQueries
import com.codingpit.muviss.feature.triage.domain.SnoozeUseCase
import com.codingpit.muviss.feature.triage.domain.TriageActions
import com.codingpit.muviss.feature.triage.domain.TriageDecisionRepository
import com.codingpit.muviss.feature.triage.domain.TriageDetailsSource
import com.codingpit.muviss.feature.triage.domain.TriageOnboarding
import com.codingpit.muviss.feature.triage.domain.TriagePreferences
import com.codingpit.muviss.feature.triage.domain.TriageSnoozeRepository
import com.codingpit.muviss.feature.triage.domain.UndoDecisionUseCase
import com.codingpit.muviss.feature.triage.domain.UnsnoozeUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Repository + use-case + [TriageApi] bindings (ADR 0010).
 *
 * [TriagePreferences] is bound over `appSettingsQueries` here rather than in
 * settings' module — see [AppSettingsTriagePreferences] for why. The
 * complementary `FeatureFlags` binding (the control scheme) does live in
 * settings' module, because a flag registry is app-global and settings owns
 * the screen that toggles it.
 */
val triageDataModule: Module = module {
    single { get<MuvissDatabase>().triageDecisionQueries }
    single<TriageDecisionRepository> { SqlDelightTriageDecisionRepository(get(), get(), get()) }
    single { get<MuvissDatabase>().triageSnoozeQueries }
    single<TriageSnoozeRepository> { SqlDelightTriageSnoozeRepository(get(), get(), get()) }
    single<TriagePreferences> { AppSettingsTriagePreferences(get<MuvissDatabase>().appSettingsQueries, get()) }

    single<DeckSource> { TmdbDeckSource(get(), get()) }
    single<TriageDetailsSource> { RegistryTriageDetailsSource(get(), get()) }
    single { DeckLoader(get()) }

    factory { RecordDecisionUseCase(get(), get(), get(), get(), get()) }
    factory { RestoreDecisionUseCase(get()) }
    factory { ObserveDecidedIdsUseCase(get()) }
    factory { ObserveSkippedUseCase(get()) }
    factory { ObserveDecisionUseCase(get()) }
    factory { LoadDeckUseCase(get(), get(), get(), get(), get()) }
    factory { LoadDeckGenresUseCase(get()) }
    factory { UndoDecisionUseCase(get(), get(), get()) }
    factory { RetryUnresolvedUseCase(get(), get()) }
    factory { ObserveTutorialSeenUseCase(get()) }
    factory { SetTutorialSeenUseCase(get()) }
    factory { SnoozeUseCase(get(), get()) }
    factory { UnsnoozeUseCase(get()) }
    factory { ObserveSnoozedUseCase(get()) }
    factory { ObserveSnoozedIdsUseCase(get()) }
    factory { ObserveSnoozeUseCase(get()) }
    factory { ObserveSnoozeHintSeenUseCase(get()) }
    factory { SetSnoozeHintSeenUseCase(get()) }
    factory { SnoozeActions(get(), get(), get()) }
    factory { SnoozeQueries(get(), get(), get(), get()) }
    factory { TriageOnboarding(get(), get()) }
    factory { TriageActions(get(), get(), get(), get(), get()) }

    single<TriageApi> { DefaultTriageApi(get(), get(), get(), get(), get()) }
}
