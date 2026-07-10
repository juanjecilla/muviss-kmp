package com.codingpit.muviss.core.network.di

import com.codingpit.muviss.core.network.DefaultMetadataLocale
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.core.network.createHttpClient
import com.codingpit.muviss.core.network.tmdb.TmdbProvider
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Wires the shared Ktor client, the region/language seam, and the registry of
 * [MetadataProvider]s. New providers register by adding another
 * `single<MetadataProvider>` here. [MetadataLocale] is bound to a constant
 * default until settings (EPIC 8) supplies a real one — this is the only
 * line that changes then.
 */
val networkModule: Module = module {
    single { createHttpClient(enableLogging = false) }
    single<MetadataLocale> { DefaultMetadataLocale() }
    single<MetadataProvider> { TmdbProvider(get(), locale = get()) }
    single { MetadataProviderRegistry(getAll<MetadataProvider>()) }
}
