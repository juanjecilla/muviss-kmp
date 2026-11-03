package com.codingpit.muviss.feature.nextpick.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.codingpit.muviss.feature.nextpick.domain.NextPickSettings
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** The Pick Session. Reached from the completion sheet and each entry point; not a bottom-bar destination. */
@Serializable
object NextPickRoute

/**
 * Registers the Pick Session. [navController] leaves the session by popping back (ADR: the shell pops to a
 * known route by name, never to the start destination, because on a first run that is onboarding).
 */
fun NavGraphBuilder.nextPickSection(navController: NavController) {
    composable<NextPickRoute> {
        val settings = koinInject<NextPickSettings>()
        PickSessionScreen(
            viewModel = koinViewModel<PickSessionViewModel>(),
            presentation = presentationFor(settings.mode),
            onLeave = { navController.popBackStack() },
        )
    }
}
