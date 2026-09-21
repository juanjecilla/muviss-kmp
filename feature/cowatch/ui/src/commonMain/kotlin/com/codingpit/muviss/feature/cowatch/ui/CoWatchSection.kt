package com.codingpit.muviss.feature.cowatch.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.codingpit.muviss.models.MediaId
import org.koin.compose.viewmodel.koinViewModel

/**
 * Co-watch's routes. Registered in the app's `NavHost`, deliberately absent
 * from the bottom bar — five top-level destinations is the design ceiling, and
 * a feature that only works once you have a linked, paying Companion is a poor
 * candidate for permanent prime real estate (Triage set the same precedent).
 *
 * [onOpenDetail] comes from the app shell rather than being resolved here: the
 * detail destination belongs to search's `:ui`, and a peer may only depend on
 * another feature's `:api` (ADR 0004).
 */
fun NavGraphBuilder.coWatchSection(navController: NavController, onOpenDetail: (MediaId) -> Unit) {
    composable<CompanionsRoute> {
        CompanionsScreen(
            viewModel = koinViewModel(),
            onBack = { navController.popBackStack() },
            onOpenShortlist = { companionUserId -> navController.navigate(ShortlistRoute(companionUserId)) },
        )
    }
    composable<ShortlistRoute> { entry ->
        ShortlistScreen(
            companionUserId = entry.toRoute<ShortlistRoute>().companionUserId,
            viewModel = koinViewModel(),
            onBack = { navController.popBackStack() },
            onOpenDetail = onOpenDetail,
        )
    }
}
