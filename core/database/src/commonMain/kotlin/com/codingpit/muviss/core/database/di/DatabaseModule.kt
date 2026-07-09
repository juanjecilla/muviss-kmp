package com.codingpit.muviss.core.database.di

import com.codingpit.muviss.core.database.createDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Provides the single [com.codingpit.muviss.core.database.MuvissDatabase]
 * instance for the app. Depends on a
 * [com.codingpit.muviss.core.database.DatabaseDriverFactory] binding supplied
 * by the platform entry point — Android needs a `Context` at construction
 * time, other platforms build one with no arguments — see `app/shared`'s
 * `rememberDatabaseDriverFactory()`.
 */
val databaseModule: Module = module {
    single { createDatabase(get()) }
}
