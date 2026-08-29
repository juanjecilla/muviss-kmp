package com.codingpit.muviss

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
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
import androidx.window.core.layout.WindowSizeClass
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.codingpit.muviss.core.common.crash.CrashReporter
import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.layout.ScreenInsets
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.sync.SignInFeedback
import com.codingpit.muviss.core.sync.SyncBackend
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
import com.codingpit.muviss.feature.triage.ui.TriageRoute
import com.codingpit.muviss.feature.triage.ui.triageSection
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
 *
 * [oauthCode] is the same idea for sign-in (ADR 0014): the authorization code
 * from a `muviss://auth-callback` redirect, cleared through
 * [onOAuthCodeConsumed] once redeemed. It is handled here rather than in the
 * profile screen because the redirect can arrive long after that screen —
 * and its ViewModel — is gone.
 */
@Suppress("DEPRECATION") // KoinApplication(config=) overload not present in this Koin version.
@Composable
fun MuvissApp(
    deepLinkMediaId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    oauthCode: String? = null,
    onOAuthCodeConsumed: () -> Unit = {},
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
        CompleteOAuthOnRedirect(oauthCode, onOAuthCodeConsumed)
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
 * Redeems an OAuth authorization code for a session (ADR 0014).
 *
 * At app scope for the same reason as [AutoSyncOnForeground], only more so:
 * the browser round-trip can outlive the profile screen entirely, and on a
 * cold start the redirect *is* the launch, so there is no ViewModel around to
 * receive it. Nothing is returned to a caller — `SyncBackend.session` is a
 * Flow, so a successful exchange reaches the profile screen on its own.
 *
 * A failure is reported through [SignInFeedback] rather than dropped. It used
 * to be dropped, on the reasoning that staying signed out was itself the
 * correct state and the recovery — which was true and still useless: sign-in
 * shipped broken, and a failure that looks identical to "not attempted" hid it
 * through three rounds of diagnosis.
 */
@Composable
private fun CompleteOAuthOnRedirect(authCode: String?, onConsumed: () -> Unit) {
    val backend = koinInject<SyncBackend>()
    val syncEngine = koinInject<SyncEngine>()
    val feedback = koinInject<SignInFeedback>()
    // The exchange deliberately runs in the composition's scope rather than the
    // effect's own. Consuming the code clears the state this effect is keyed
    // on, and a LaunchedEffect is cancelled the moment its key changes — so
    // doing the work inside it would abort at the first suspension point,
    // which is the exchange itself. Sign-in then failed silently every time.
    // rememberCoroutineScope outlives a key change; it ends when the composable
    // leaves composition.
    val scope = rememberCoroutineScope()
    LaunchedEffect(authCode) {
        val code = authCode ?: return@LaunchedEffect
        // Still consumed immediately: the code is single-use server-side, and
        // re-delivering the same intent must not redeem it twice.
        onConsumed()
        scope.launch {
            backend.completeOAuth(code)
                .onSuccess { syncEngine.syncNow() }
                // Reported rather than swallowed: this is the only place that
                // knows why sign-in failed, and the profile screen is the only
                // place that can say so.
                .onFailure { feedback.report(it.message) }
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

    // Window-size-class-driven navigation: bottom bar on compact (<600dp),
    // nav rail on medium and expanded — a rail keeps posters full-width and
    // five destinations never warrant a drawer (design doc §06).
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val layoutType =
        if (windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) {
            NavigationSuiteType.NavigationRail
        } else {
            NavigationSuiteType.NavigationBar
        }

    // Amber pill indicator + amber selected label, per the design mockups.
    // Built here because the navigationSuiteItems DSL is not composable.
    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    )

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            navigationRailContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        navigationSuiteItems = {
            topDestinations.forEach { dest ->
                val selected =
                    currentDestination?.hierarchy?.any {
                        it.hasRoute(dest.route::class)
                    } == true
                item(
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
                    colors = itemColors,
                )
            }
        },
    ) {
        // The nav bar and rail inset themselves, but NavigationSuiteScaffold
        // hands its content no padding and takes no contentWindowInsets — so
        // without this every screen not built on an M3 Scaffold draws its first
        // pixel under the status bar and camera cutout. One wrap fixes all of
        // them, on every platform.
        ScreenInsets {
            NavHost(
                navController = navController,
                startDestination = SearchRoute,
                // M3 fade-through: outgoing fades and settles to 0.92, incoming
                // fades in from 1.02 — every destination inherits it from here.
                enterTransition = {
                    fadeIn(tween(durationMillis = 210, delayMillis = 90)) +
                        scaleIn(initialScale = 1.02f, animationSpec = tween(durationMillis = 210, delayMillis = 90))
                },
                exitTransition = {
                    fadeOut(tween(durationMillis = 90)) +
                        scaleOut(targetScale = 0.92f, animationSpec = tween(durationMillis = 90))
                },
                popEnterTransition = {
                    fadeIn(tween(durationMillis = 210, delayMillis = 90)) +
                        scaleIn(initialScale = 0.92f, animationSpec = tween(durationMillis = 210, delayMillis = 90))
                },
                popExitTransition = {
                    fadeOut(tween(durationMillis = 90)) +
                        scaleOut(targetScale = 1.02f, animationSpec = tween(durationMillis = 90))
                },
            ) {
                searchSection(navController, onOpenTriage = { navController.navigate(TriageRoute) })
                collectionSection(navController, onOpenDetail = { id -> navController.navigate(DetailRoute(id.toString())) })
                progressSection(onOpenDetail = { id -> navController.navigate(DetailRoute(id.toString())) })
                profileSection()
                settingsSection(navController, onOpenTriage = { navController.navigate(TriageRoute) })
                // Not a top-level destination — reached from Discover and Settings.
                triageSection(navController, onOpenDetail = { id -> navController.navigate(DetailRoute(id.toString())) })
            }
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
