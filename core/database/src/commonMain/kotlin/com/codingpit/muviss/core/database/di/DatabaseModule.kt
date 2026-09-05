package com.codingpit.muviss.core.database.di

import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.core.database.PersistenceStatus
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

    // Whether this session's writes survive (issue #53). Bound here rather than
    // beside the `DatabaseDriverFactory` in `MuvissApp`'s own module, because
    // that module only applies on the platforms where `MuvissApp` starts Koin
    // itself. Android, iOS and desktop start it first — in `MuvissApplication`,
    // `IosAppStartup` and `Main.kt` — and `MuvissApp`'s `KoinApplication` then
    // reuses the running instance, so a binding declared there never loads and
    // the injection point crashes on the first frame. `databaseModule` is in
    // `appModules`, which every one of those entry points passes to `startKoin`.
    //
    // The factory is still what answers it: on web the answer comes from the
    // very Web Worker it spawns, and there is no second place to ask.
    single<PersistenceStatus> { get<DatabaseDriverFactory>().persistence }
}
