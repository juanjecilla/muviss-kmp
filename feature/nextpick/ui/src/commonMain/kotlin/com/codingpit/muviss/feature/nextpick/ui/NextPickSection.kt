package com.codingpit.muviss.feature.nextpick.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.codingpit.muviss.models.MediaSummary
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The Pick Session. Reached from the completion sheet and each entry point; not a bottom-bar destination.
 * [anchor] is the title a Completion just finished, as [com.codingpit.muviss.models.MediaId.toString] (a String,
 * because a navigation argument that is a class needs a custom NavType), or null from any other entry point.
 */
@Serializable
data class NextPickRoute(val anchor: String? = null)

/**
 * Registers the Pick Session. [navController] leaves the session by popping back (ADR: the shell pops to a
 * known route by name, never to the start destination, because on a first run that is the introduction).
 * [onOpenDeck] hands the Deck's titles to Triage; the app shell owns that handoff because Triage's deck is
 * reached through its own route, which this feature may not name (ADR 0004).
 */
fun NavGraphBuilder.nextPickSection(
    navController: NavController,
    onOpenDeck: (List<MediaSummary>) -> Unit = {},
) {
    composable<NextPickRoute> { entry ->
        val route = entry.toRoute<NextPickRoute>()
        PickSessionScreen(
            viewModel = koinViewModel<PickSessionViewModel> { parametersOf(route.anchor) },
            onLeave = { navController.popBackStack() },
            onOpenDeck = onOpenDeck,
        )
    }
}
