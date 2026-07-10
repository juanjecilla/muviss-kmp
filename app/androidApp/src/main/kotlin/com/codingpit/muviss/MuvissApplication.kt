package com.codingpit.muviss

import android.app.Application
import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.di.appModules
import com.codingpit.muviss.notifications.NewEpisodesScheduler
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Android application entry point.
 *
 * Starts Koin here rather than relying solely on `MuvissApp()`'s
 * `org.koin.compose.KoinApplication` composable, so EPIC 5's
 * `NewEpisodesWorker` can resolve its dependencies even when WorkManager
 * cold-starts this process with no Activity/Compose tree at all (e.g. a
 * periodic run while the app hasn't been opened since boot). Guarded by
 * [GlobalContext.getOrNull] in case something else already started it first.
 *
 * `MuvissApp()` composes its own `KoinApplication` with the same module
 * list; per its doc comment, when a global Koin instance already exists
 * (the normal case once this runs) it reuses that instance instead of
 * starting a second one, so there's exactly one Koin graph either way.
 */
class MuvissApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                modules(appModules + module { single { DatabaseDriverFactory(this@MuvissApplication) } })
            }
        }
        NewEpisodesScheduler.schedule(this)
    }
}
