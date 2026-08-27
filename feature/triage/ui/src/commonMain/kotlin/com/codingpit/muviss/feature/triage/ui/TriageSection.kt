package com.codingpit.muviss.feature.triage.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.codingpit.muviss.models.MediaId
import org.koin.compose.viewmodel.koinViewModel

/**
 * Triage's routes. Registered in the app's `NavHost` but deliberately absent
 * from the bottom bar: the deck is reached from Discover and from Settings,
 * and five top-level destinations is the design ceiling.
 *
 * [onOpenDetail] is supplied by the app shell rather than resolved here: the
 * detail destination belongs to search's `:ui`, and a peer may only depend on
 * another feature's `:api` (ADR 0004). Same shape as `collectionSection` and
 * `progressSection`.
 */
fun NavGraphBuilder.triageSection(navController: NavController, onOpenDetail: (MediaId) -> Unit) {
    composable<TriageRoute> {
        TriageScreen(
            viewModel = koinViewModel(),
            onBack = { navController.popBackStack() },
            onOpenSkipped = { navController.navigate(SkippedRoute) },
            onOpenDetail = onOpenDetail,
        )
    }
    composable<SkippedRoute> {
        SkippedScreen(
            viewModel = koinViewModel(),
            onBack = { navController.popBackStack() },
            onOpenDetail = onOpenDetail,
        )
    }
}
