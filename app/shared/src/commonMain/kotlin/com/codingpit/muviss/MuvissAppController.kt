package com.codingpit.muviss

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.codingpit.muviss.app.shared.generated.resources.Res
import com.codingpit.muviss.app.shared.generated.resources.nav_library
import com.codingpit.muviss.app.shared.generated.resources.nav_profile
import com.codingpit.muviss.app.shared.generated.resources.nav_progress
import com.codingpit.muviss.app.shared.generated.resources.nav_search
import com.codingpit.muviss.app.shared.generated.resources.nav_settings
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.feature.collection.ui.CollectionRoute
import com.codingpit.muviss.feature.profile.ui.ProfileRoute
import com.codingpit.muviss.feature.progress.ui.ProgressRoute
import com.codingpit.muviss.feature.search.ui.SearchRoute
import com.codingpit.muviss.feature.settings.ui.SettingsRoute
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One of the five top-level destinations.
 *
 * Public so a platform host can build its own navigation affordance out of
 * them — desktop's menu bar is the first — but only [key] and [label] are
 * public with it. [key] is a stable identifier that is never shown (desktop
 * keys its keyboard shortcuts by it); [label] is the localized name, read from
 * `:app:shared`'s string resources in the composition's locale.
 * The route object comes from a feature's `:ui` module and the icon is a
 * design-system `ImageVector`; both are `implementation` dependencies of
 * `:app:shared`, so exposing either would put them on every host's compile
 * classpath to render a menu item's text.
 */
@Stable
class MuvissDestination internal constructor(
    val key: String,
    internal val title: StringResource,
    internal val route: Any,
    internal val icon: ImageVector,
) {
    val label: String
        @Composable get() = stringResource(title)
}

/**
 * The app's navigation, hoisted far enough out of [MuvissApp] that a platform
 * host can drive it.
 *
 * It exists because Compose Desktop's `MenuBar` is a composable of the
 * `Window`'s own scope, in `app/desktopApp`'s `Main.kt` — it is not inside
 * [MuvissApp] and cannot reach the `NavHostController` that used to be created
 * there (issue #41). Something had to be hoisted; this is the smallest thing
 * that could be, and deliberately not the `NavHostController` itself:
 * `libs.navigation.compose` is an `implementation` dependency of `:app:shared`,
 * so hoisting the controller would mean putting androidx.navigation on the
 * desktop host's compile classpath purely to drive a menu.
 *
 * It also owns the `navigate` options rather than leaving them at the call
 * site. There are now two ways to reach a top-level destination — the
 * navigation bar/rail and the menu — and if only one of them saved and
 * restored state the two would disagree about the back stack within a few
 * clicks.
 *
 * [currentDestination] is snapshot state rather than a `Flow`, so a `MenuBar`
 * composed in a different composition than the `NavHost` still recomposes when
 * it changes. It is written by `MuvissScaffold`, which is the only thing that
 * observes the back stack.
 */
@Stable
class MuvissAppController internal constructor(
    internal val navController: NavHostController,
) {
    /** The five top-level destinations, in bar/rail order. Same list as [muvissDestinations]. */
    val destinations: List<MuvissDestination> get() = muvissDestinations

    /** The top-level destination currently selected, or null before the first frame. */
    var currentDestination: MuvissDestination? by mutableStateOf(null)
        internal set

    /**
     * Navigates to a top-level destination exactly as the navigation bar does.
     *
     * `popUpTo(startDestination) { saveState }` + `restoreState` is what makes
     * the five tabs behave like tabs: each keeps its own back stack and its own
     * scroll position, and the app never accumulates a stack of them.
     * `launchSingleTop` stops re-selecting the current one from stacking a
     * duplicate.
     */
    fun navigateTo(destination: MuvissDestination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

/**
 * Creates the [MuvissAppController] for a composition.
 *
 * [MuvissApp] calls this itself by default, which is what every platform but
 * desktop wants. Desktop calls it in the `Window` scope instead and passes the
 * result in, so its `MenuBar` and the app share one controller.
 */
@Composable
fun rememberMuvissAppController(): MuvissAppController {
    val navController = rememberNavController()
    return remember(navController) { MuvissAppController(navController) }
}

/**
 * The five top-level destinations, in navigation bar/rail order.
 *
 * Top-level rather than only reachable through a [MuvissAppController]
 * instance, so a host can check its own navigation affordance against it
 * without building one — `app/desktopApp`'s menu keys its keyboard shortcuts
 * by [MuvissDestination.key], and a test there asserts those keys are really
 * in this list. A renamed key would otherwise drop a shortcut in silence.
 * (Keyed by [MuvissDestination.key], not the label, since EPIC 31: the label
 * is now whatever language the user reads.)
 */
val muvissDestinations: List<MuvissDestination> =
    listOf(
        MuvissDestination("search", Res.string.nav_search, SearchRoute, MuvissIcons.Search),
        MuvissDestination("library", Res.string.nav_library, CollectionRoute, MuvissIcons.Library),
        MuvissDestination("progress", Res.string.nav_progress, ProgressRoute, MuvissIcons.WatchNext),
        MuvissDestination("profile", Res.string.nav_profile, ProfileRoute, MuvissIcons.Profile),
        MuvissDestination("settings", Res.string.nav_settings, SettingsRoute, MuvissIcons.Settings),
    )
