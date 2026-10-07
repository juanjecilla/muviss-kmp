package com.codingpit.muviss.feature.settings.data.di

import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.core.network.MutableMetadataLocale
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.settings.data.AppSettingsFeatureFlags
import com.codingpit.muviss.feature.settings.data.DefaultSettingsApi
import com.codingpit.muviss.feature.settings.data.ExportQueries
import com.codingpit.muviss.feature.settings.data.RegistryImportMediaDetailsSource
import com.codingpit.muviss.feature.settings.data.SettingsLocaleSync
import com.codingpit.muviss.feature.settings.data.SqlDelightSettingsRepository
import com.codingpit.muviss.feature.settings.data.TmdbExternalIdResolver
import com.codingpit.muviss.feature.settings.domain.ApplyImportUseCase
import com.codingpit.muviss.feature.settings.domain.ExportDataUseCase
import com.codingpit.muviss.feature.settings.domain.ExternalIdResolver
import com.codingpit.muviss.feature.settings.domain.GenericCsvImportParser
import com.codingpit.muviss.feature.settings.domain.ImportActions
import com.codingpit.muviss.feature.settings.domain.ImportMediaDetailsSource
import com.codingpit.muviss.feature.settings.domain.ImportParser
import com.codingpit.muviss.feature.settings.domain.ObserveSettingsUseCase
import com.codingpit.muviss.feature.settings.domain.PreviewImportUseCase
import com.codingpit.muviss.feature.settings.domain.SetCrashReportsEnabledUseCase
import com.codingpit.muviss.feature.settings.domain.SetLanguageUseCase
import com.codingpit.muviss.feature.settings.domain.SetNotificationsEnabledUseCase
import com.codingpit.muviss.feature.settings.domain.SetRegionUseCase
import com.codingpit.muviss.feature.settings.domain.SetThemeUseCase
import com.codingpit.muviss.feature.settings.domain.SettingsActions
import com.codingpit.muviss.feature.settings.domain.SettingsRepository
import com.codingpit.muviss.feature.settings.domain.TraktImportParser
import com.codingpit.muviss.feature.settings.domain.TvTimeImportParser
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
 *
 * EPIC 18 (import) bindings: the three [ImportParser]s are constructed
 * directly (they're stateless, no need for Koin to manage them individually)
 * and handed to [PreviewImportUseCase] as a list; [TmdbExternalIdResolver]
 * and [RegistryImportMediaDetailsSource] are this feature's own network-
 * touching seams (see their KDoc for why they aren't collection/progress's).
 */
val settingsDataModule: Module = module {
    single { get<MuvissDatabase>().appSettingsQueries }
    single<SettingsRepository> {
        SqlDelightSettingsRepository(
            get(),
            ExportQueries(get<MuvissDatabase>()),
            get(),
            get(),
            get(),
        )
    }
    single<SettingsApi> { DefaultSettingsApi(get()) }
    single<FeatureFlags> { AppSettingsFeatureFlags(get(), get()) }

    single { MutableMetadataLocale() } bind MetadataLocale::class
    single { SettingsLocaleSync(get(), get(), get(), get()) } withOptions { createdAtStart() }

    factory { ObserveSettingsUseCase(get()) }
    factory { SetThemeUseCase(get()) }
    factory { SetLanguageUseCase(get()) }
    factory { SetRegionUseCase(get()) }
    factory { SetNotificationsEnabledUseCase(get()) }
    factory { SetCrashReportsEnabledUseCase(get()) }
    factory { ExportDataUseCase(get()) }
    factory { SettingsActions(get(), get(), get(), get(), get(), get()) }

    single<ExternalIdResolver> { TmdbExternalIdResolver(get(), get()) }
    single<ImportMediaDetailsSource> { RegistryImportMediaDetailsSource(get(), get()) }
    factory { listOf<ImportParser>(TraktImportParser(), TvTimeImportParser(), GenericCsvImportParser()) }
    factory { PreviewImportUseCase(get(), get()) }
    factory { ApplyImportUseCase(get(), get(), get()) }
    factory { ImportActions(get(), get()) }
}
