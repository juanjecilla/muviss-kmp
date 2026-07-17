package com.codingpit.muviss

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.codingpit.muviss.core.common.crash.CrashReporter
import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.di.appModules
import com.codingpit.muviss.di.rememberDatabaseDriverFactory
import com.codingpit.muviss.feature.collection.ui.CollectionRoute
import com.codingpit.muviss.feature.collection.ui.collectionSection
import com.codingpit.muviss.feature.profile.ui.ProfileRoute
import com.codingpit.muviss.feature.profile.ui.profileSection
import com.codingpit.muviss.feature.progress.ui.ProgressRoute
import com.codingpit.muviss.feature.progress.ui.progressSection
import com.codingpit.muviss.feature.search.ui.DetailRoute
import com.codingpit.muviss.feature.search.ui.SearchRoute
import com.codingpit.muviss.feature.search.ui.searchSection
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.settings.api.ThemeMode
import com.codingpit.muviss.feature.settings.ui.SettingsRoute
import com.codingpit.muviss.feature.settings.ui.settingsSection
import kotlinx.coroutines.launch
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.core.module.Module
import org.koin.dsl.module

private data class TopDestination(
    val route: Any,
    val label: String,
    val icon: ImageVector,
)

private val topDestinations =
    listOf(
        TopDestination(SearchRoute, "Search", MuvissIcons.Search),
        TopDestination(CollectionRoute, "Library", MuvissIcons.Library),
        TopDestination(ProgressRoute, "Progress", MuvissIcons.WatchNext),
        TopDestination(ProfileRoute, "Profile", MuvissIcons.Profile),
        TopDestination(SettingsRoute, "Settings", MuvissIcons.Settings),
    )

/**
 * Root entry point for every platform. Starts Koin (or, on Android, reuses
 * the instance `MuvissApplication` already started at process start so the
 * EPIC 5 background worker can reach it too — see [org.koin.compose.KoinApplication],
 * which no-ops its own `startKoin` when a global instance already exists)
 * and hosts the app.
 *
 * [deepLinkMediaId] carries the media id from an Android notification tap
 * (`DetailRoute`'s string form); once consumed the caller clears it via
 * [onDeepLinkConsumed] so backgrounding/foregrounding the app doesn't
 * re-navigate. Both default to no-op for platforms with no such deep link.
 */
@Suppress("DEPRECATION") // KoinApplication(config=) overload not present in this Koin version.
@Composable
fun MuvissApp(
    deepLinkMediaId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    remember { CrashReporter.init(MuvissBuildConfig.SENTRY_DSN) }
    remember { configureImageLoader() }
    val databaseDriverFactory = rememberDatabaseDriverFactory()
    KoinApplication(application = { modules(appModules + platformDatabaseModule(databaseDriverFactory)) }) {
        val settingsApi = koinInject<SettingsApi>()
        val themeMode by settingsApi.observeThemeMode().collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
        val darkTheme = when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
        AutoSyncOnForeground()
        MuvissTheme(darkTheme = darkTheme) {
            MuvissScaffold(deepLinkMediaId, onDeepLinkConsumed)
        }
    }
}

/**
 * Best-effort auto-sync (EPIC 9) whenever the app returns to the foreground.
 * Lives here rather than in the profile feature because it must fire no
 * matter which screen is visible — `ProfileViewModel`'s lifetime is scoped
 * to the Profile screen's own back-stack entry, this is scoped to the whole
 * app. Failures are swallowed: [SyncEngine.syncNow] already turns them into
 * a `SyncOutcome.Failed` return value rather than throwing, and there's no
 * single screen to surface a message on from here — the profile screen's
 * own "last synced" label is the source of truth for whether it's working.
 * A no-op when sync isn't signed in or not configured for this build (see
 * `NoOpSyncBackend`/`SyncOutcome.NotSignedIn`).
 */
@Composable
private fun AutoSyncOnForeground() {
    val syncEngine = koinInject<SyncEngine>()
    val coroutineScope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        coroutineScope.launch { syncEngine.syncNow() }
    }
}

/**
 * Binds the platform-built [DatabaseDriverFactory] instance into the Koin
 * graph. It has to be created in composition (Android needs a `Context`), so
 * it cannot live in the common [appModules] list alongside `databaseModule`.
 */
private fun platformDatabaseModule(driverFactory: DatabaseDriverFactory): Module = module {
    single { driverFactory }
}

@Composable
private fun MuvissScaffold(
    deepLinkMediaId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    LaunchedEffect(deepLinkMediaId) {
        if (deepLinkMediaId != null) {
            navController.navigate(DetailRoute(deepLinkMediaId))
            onDeepLinkConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                topDestinations.forEach { dest ->
                    val selected =
                        currentDestination?.hierarchy?.any {
                            it.hasRoute(dest.route::class)
                        } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = SearchRoute,
            modifier = Modifier.padding(padding),
        ) {
            searchSection(navController)
            collectionSection(navController, onOpenDetail = { id -> navController.navigate(DetailRoute(id.toString())) })
            progressSection(onOpenDetail = { id -> navController.navigate(DetailRoute(id.toString())) })
            profileSection()
            settingsSection(navController)
        }
    }
}

private fun configureImageLoader() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader
            .Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .build()
    }
}
