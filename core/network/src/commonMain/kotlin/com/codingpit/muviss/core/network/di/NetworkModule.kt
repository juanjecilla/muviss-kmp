package com.codingpit.muviss.core.network.di

import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.core.network.createHttpClient
import com.codingpit.muviss.core.network.tmdb.TmdbFailureTrace
import com.codingpit.muviss.core.network.tmdb.TmdbProvider
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Wires the shared Ktor client and the registry of [MetadataProvider]s. New
 * providers register by adding another `single<MetadataProvider>` here.
 *
 * [MetadataLocale] is *not* bound here: `:core:network` sits below the
 * feature layer (ADR 0004), so it can't depend on settings to supply a real
 * one. `feature/settings/data`'s Koin module binds it instead (a
 * `MutableMetadataLocale`, kept in sync with the user's saved language/region
 * by that module's `SettingsLocaleSync`) — this module only needs the
 * interface to exist to wire [TmdbProvider].
 */
val networkModule: Module = module {
    single { createHttpClient(enableLogging = false) }
    // No-op here; a host overrides it in debug builds only (#177). Koin's last
    // binding wins, so the host's module must load after this one.
    single<TmdbFailureTrace> { TmdbFailureTrace.None }
    single<MetadataProvider> { TmdbProvider(get(), get(), get()) }
    single { MetadataProviderRegistry(getAll<MetadataProvider>()) }
}
