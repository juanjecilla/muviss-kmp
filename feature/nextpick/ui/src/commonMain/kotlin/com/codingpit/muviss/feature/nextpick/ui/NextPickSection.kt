package com.codingpit.muviss.feature.nextpick.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.codingpit.muviss.models.MediaSummary
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel

/** The Pick Session. Reached from the completion sheet and each entry point; not a bottom-bar destination. */
@Serializable
object NextPickRoute

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
    composable<NextPickRoute> {
        PickSessionScreen(
            viewModel = koinViewModel<PickSessionViewModel>(),
            onLeave = { navController.popBackStack() },
            onOpenDeck = onOpenDeck,
        )
    }
}
