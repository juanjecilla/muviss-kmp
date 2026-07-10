package com.codingpit.muviss.feature.settings.data.di

import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.core.network.MutableMetadataLocale
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.settings.data.DefaultSettingsApi
import com.codingpit.muviss.feature.settings.data.SettingsLocaleSync
import com.codingpit.muviss.feature.settings.data.SqlDelightSettingsRepository
import com.codingpit.muviss.feature.settings.domain.ExportDataUseCase
import com.codingpit.muviss.feature.settings.domain.ObserveSettingsUseCase
import com.codingpit.muviss.feature.settings.domain.SetLanguageUseCase
import com.codingpit.muviss.feature.settings.domain.SetNotificationsEnabledUseCase
import com.codingpit.muviss.feature.settings.domain.SetRegionUseCase
import com.codingpit.muviss.feature.settings.domain.SetThemeUseCase
import com.codingpit.muviss.feature.settings.domain.SettingsActions
import com.codingpit.muviss.feature.settings.domain.SettingsRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Repository + use-case + [SettingsApi] bindings for the settings feature.
 * Also binds `:core:network`'s [MetadataLocale] (see that interface's doc)
 * to a live [MutableMetadataLocale], and starts [SettingsLocaleSync] eagerly
 * so it stays in sync from app startup.
 */
val settingsDataModule: Module = module {
    single { get<MuvissDatabase>().appSettingsQueries }
    single<SettingsRepository> {
        SqlDelightSettingsRepository(get(), get<MuvissDatabase>().collectionEntryQueries, get<MuvissDatabase>().episodeProgressQueries, get(), get())
    }
    single<SettingsApi> { DefaultSettingsApi(get()) }

    single { MutableMetadataLocale() } bind MetadataLocale::class
    single { SettingsLocaleSync(get(), get(), get()) } withOptions { createdAtStart() }

    factory { ObserveSettingsUseCase(get()) }
    factory { SetThemeUseCase(get()) }
    factory { SetLanguageUseCase(get()) }
    factory { SetRegionUseCase(get()) }
    factory { SetNotificationsEnabledUseCase(get()) }
    factory { ExportDataUseCase(get()) }
    factory { SettingsActions(get(), get(), get(), get(), get()) }
}
