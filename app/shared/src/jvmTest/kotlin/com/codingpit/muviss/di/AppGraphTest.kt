package com.codingpit.muviss.di

import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.ListsApi
import com.codingpit.muviss.feature.profile.api.ProfileApi
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.triage.api.TriageApi
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Assembles the real app graph and resolves every feature's public contract.
 *
 * This exists because of a bug that shipped past every other test in the
 * repo. Adding `observeWatchNext()` to `ProgressApi` (EPIC 22) meant
 * `DefaultProgressApi` needed a `CollectionApi`, while collection's
 * repository has always needed a `ProgressApi` to derive `WatchStatus` (ADR
 * 0005). Koin followed the cycle until the stack overflowed — on the first
 * frame, before anything rendered. Two thousand unit tests were green,
 * `./gradlew build` was green, and the app died on launch, because every
 * suite builds the objects it needs by hand and none of them had ever
 * assembled the graph the app actually runs on.
 *
 * `Module.verify()` would not have caught it either: it checks that every
 * constructor parameter has *a* definition, without instantiating anything,
 * and a cycle is only visible when something is built. So this resolves for
 * real.
 *
 * `:app:shared` is the only module that can host it — the only one that sees
 * `appModules`. The database goes in a temp directory rather than the
 * developer's real app-data directory, which is why the JVM
 * `DatabaseDriverFactory` takes one.
 */
class AppGraphTest {

    private var koin: Koin? = null

    @AfterTest
    fun tearDown() {
        koin?.let { stopKoin() }
        koin = null
    }

    private fun startGraph(): Koin {
        val directory = createTempDirectory("muviss-app-graph").toFile()
        val app = startKoin {
            modules(appModules + module { single { DatabaseDriverFactory(directory) } })
        }
        koin = app.koin
        return app.koin
    }

    @Test
    fun `every feature contract resolves from the assembled graph`() {
        val koin = startGraph()

        // Resolving is the assertion: a missing binding throws, and a cycle
        // between two of these overflows the stack. `SearchApi` is absent on
        // purpose — that module declares a contract nothing implements or
        // binds, so search is reached through its own ViewModels only.
        assertNotNull(koin.get<CollectionApi>())
        assertNotNull(koin.get<ProgressApi>())
        assertNotNull(koin.get<SettingsApi>())
        assertNotNull(koin.get<ProfileApi>())
        assertNotNull(koin.get<TriageApi>())
        assertNotNull(koin.get<ListsApi>())
        assertNotNull(koin.get<SyncEngine>())
    }

    /**
     * The specific cycle, named so a future reader knows why the order below
     * matters: resolving collection *first* builds progress underneath it,
     * and resolving progress first builds collection underneath that. Both
     * directions have to terminate.
     */
    @Test
    fun `collection and progress resolve in either order`() {
        startGraph().let { koin ->
            assertNotNull(koin.get<CollectionApi>())
            assertNotNull(koin.get<ProgressApi>())
        }
        tearDown()

        startGraph().let { koin ->
            assertNotNull(koin.get<ProgressApi>())
            assertNotNull(koin.get<CollectionApi>())
        }
    }
}
