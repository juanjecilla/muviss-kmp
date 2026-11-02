package com.codingpit.muviss.feature.onboarding.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import org.koin.compose.viewmodel.koinViewModel

/**
 * Registers the introduction. [onFinished] is told whether this was a replay, so the shell can
 * either start the app (first run) or go back to Settings (replay). [onOpenImport] opens the
 * import screen, which belongs to Settings' `:ui` (a peer, ADR 0004), so the shell owns that hop.
 */
fun NavGraphBuilder.onboardingSection(
    onFinished: (replay: Boolean) -> Unit,
    onOpenImport: () -> Unit,
) {
    composable<OnboardingRoute> { entry ->
        val route = entry.toRoute<OnboardingRoute>()
        val viewModel = koinViewModel<OnboardingViewModel>()
        OnboardingScreen(viewModel, onFinished = { onFinished(route.replay) }, onOpenImport = onOpenImport)
    }
}
