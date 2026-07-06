package com.codingpit.muviss.core.network.di

import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.core.network.createHttpClient
import com.codingpit.muviss.core.network.tmdb.TmdbProvider
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Wires the shared Ktor client and the registry of [MetadataProvider]s. New
 * providers register by adding another `single<MetadataProvider>` here.
 */
val networkModule: Module = module {
    single { createHttpClient(enableLogging = false) }
    single<MetadataProvider> { TmdbProvider(get()) }
    single { MetadataProviderRegistry(getAll<MetadataProvider>()) }
}
