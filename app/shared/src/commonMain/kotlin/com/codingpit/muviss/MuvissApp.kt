package com.codingpit.muviss

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
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
import com.codingpit.muviss.feature.settings.ui.SettingsRoute
import com.codingpit.muviss.feature.settings.ui.settingsSection
import org.koin.compose.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.module

private data class TopDestination(
    val route: Any,
    val label: String,
)

private val topDestinations =
    listOf(
        TopDestination(SearchRoute, "Search"),
        TopDestination(CollectionRoute, "Library"),
        TopDestination(ProgressRoute, "Progress"),
        TopDestination(ProfileRoute, "Profile"),
        TopDestination(SettingsRoute, "Settings"),
    )

/** Root entry point for every platform. Starts Koin and hosts the app. */
@Suppress("DEPRECATION") // KoinApplication(config=) overload not present in this Koin version.
@Composable
fun MuvissApp() {
    remember { CrashReporter.init(MuvissBuildConfig.SENTRY_DSN) }
    remember { configureImageLoader() }
    val databaseDriverFactory = rememberDatabaseDriverFactory()
    KoinApplication(application = { modules(appModules + platformDatabaseModule(databaseDriverFactory)) }) {
        MuvissTheme {
            MuvissScaffold()
        }
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
private fun MuvissScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

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
                        icon = {},
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
            collectionSection(onOpenDetail = { id -> navController.navigate(DetailRoute(id.toString())) })
            progressSection(onOpenDetail = { id -> navController.navigate(DetailRoute(id.toString())) })
            profileSection()
            settingsSection()
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
