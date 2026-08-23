package com.codingpit.muviss.feature.triage.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import org.koin.compose.viewmodel.koinViewModel

/**
 * Triage's routes. Registered in the app's `NavHost` but deliberately absent
 * from the bottom bar: the deck is reached from Discover and from Settings,
 * and five top-level destinations is the design ceiling.
 */
fun NavGraphBuilder.triageSection(navController: NavController) {
    composable<TriageRoute> {
        TriageScreen(
            viewModel = koinViewModel(),
            onBack = { navController.popBackStack() },
            onOpenSkipped = { navController.navigate(SkippedRoute) },
        )
    }
    composable<SkippedRoute> {
        SkippedScreen(
            viewModel = koinViewModel(),
            onBack = { navController.popBackStack() },
        )
    }
}
