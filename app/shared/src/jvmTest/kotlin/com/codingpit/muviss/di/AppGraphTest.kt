package com.codingpit.muviss.di

import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.notifications.NotificationPermissionRequester
import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import com.codingpit.muviss.core.common.secrets.InMemorySecretStore
import com.codingpit.muviss.core.common.secrets.SecretStore
import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.core.database.PersistenceStatus
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.sync.AutomaticSyncSettings
import com.codingpit.muviss.core.sync.SyncCoordinator
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.ListsApi
import com.codingpit.muviss.feature.cowatch.api.CoWatchApi
import com.codingpit.muviss.feature.nextpick.api.NextPickPreferences
import com.codingpit.muviss.feature.nextpick.domain.CompletionPromptStore
import com.codingpit.muviss.feature.nextpick.domain.NextPickActions
import com.codingpit.muviss.feature.nextpick.domain.PickContextSource
import com.codingpit.muviss.feature.nextpick.domain.PickEngine
import com.codingpit.muviss.feature.nextpick.ui.CompletionPromptViewModel
import com.codingpit.muviss.feature.onboarding.api.OnboardingApi
import com.codingpit.muviss.feature.onboarding.ui.OnboardingViewModel
import com.codingpit.muviss.feature.profile.api.ProfileApi
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.settings.ui.NextPickSettingsViewModel
import com.codingpit.muviss.feature.triage.api.TriageApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
            modules(
                appModules + module {
                    single { DatabaseDriverFactory(directory) }
                    single<SecretStore> { InMemorySecretStore() }
                },
            )
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
        assertNotNull(koin.get<CoWatchApi>())
        assertNotNull(koin.get<SyncEngine>())
        // Bound by `databaseModule` rather than by `MuvissApp`'s own module, so
        // that it exists on the platforms that start Koin before Compose — this
        // test's `startGraph` is the shape of those entry points, and it is the
        // only place that would notice if the binding moved back.
        assertNotNull(koin.get<PersistenceStatus>())
        // EPIC 40: the coordinator and the settings it follows are part of the graph.
        assertNotNull(koin.get<SyncCoordinator>())
        assertNotNull(koin.get<AutomaticSyncSettings>())
        // EPIC 45: the provider needs RemoteConfig for its per-request credential, and FeatureFlags
        // reads byokEnabled from it; neither resolves unless remoteConfigModule is in the graph.
        assertNotNull(koin.get<RemoteConfig>())
        assertNotNull(koin.get<MetadataProvider>())
        assertNotNull(koin.get<FeatureFlags>())
        // EPIC 47: the nudge reads the library and Settings through their :api, resolved lazily; the
        // contract must resolve in both orders with the rest of the graph.
        assertNotNull(koin.get<OnboardingApi>())
        assertNotNull(koin.get<NotificationPermissionRequester>())
        // EPIC 51 (#400): Next Pick's engine, its context source, actions and the sheet's memory resolve
        // through the peers' :api, in this graph's order.
        assertNotNull(koin.get<PickEngine>())
        assertNotNull(koin.get<PickContextSource>())
        assertNotNull(koin.get<NextPickActions>())
        assertNotNull(koin.get<CompletionPromptStore>())
        // EPIC 51 (#403): the Next Pick preferences are bound by settings:data and read by the Pick Session (#400).
        assertNotNull(koin.get<NextPickPreferences>())
    }

    /** The Next Pick section of Settings (#403): its view model takes two contracts, so it resolves from the real graph. */
    @Test
    fun `the next pick settings view model resolves from the assembled graph`() {
        assertNotNull(startGraph().get<NextPickSettingsViewModel>())
    }

    /**
     * The first-run introduction's view model is the first thing a fresh install builds. Its
     * constructor has a defaulted `Boolean` (Device Backup's `canBackUp`), which `viewModelOf` would
     * try to resolve from Koin and fail on; that shipped to CI as an iOS launch crash.
     */
    @Test
    fun `the onboarding view model resolves from the assembled graph`() {
        assertNotNull(startGraph().get<OnboardingViewModel>())
    }

    /** EPIC 51 (#400): the completion sheet's ViewModel is hosted at the app root, so it is built on every launch. */
    @Test
    fun `the completion prompt view model resolves from the assembled graph`() {
        assertNotNull(startGraph().get<CompletionPromptViewModel>())
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

    /**
     * EPIC 41's version of the same hazard. Co-watch builds its Watch Pool from
     * `CollectionApi` and `ListsApi`, and collection's repository already
     * reaches for `ProgressApi` to derive status — so taking those peers
     * eagerly would close a construction cycle. They are injected as providers
     * for exactly that reason, and this is what proves it: resolving co-watch
     * first has to terminate just as resolving collection first does.
     */
    @Test
    fun `co-watch and its peers resolve in either order`() {
        startGraph().let { koin ->
            assertNotNull(koin.get<CoWatchApi>())
            assertNotNull(koin.get<CollectionApi>())
            assertNotNull(koin.get<ListsApi>())
        }
        tearDown()

        startGraph().let { koin ->
            assertNotNull(koin.get<CollectionApi>())
            assertNotNull(koin.get<ListsApi>())
            assertNotNull(koin.get<CoWatchApi>())
        }
    }
}
