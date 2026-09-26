package com.codingpit.muviss.feature.profile.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import org.koin.compose.viewmodel.koinViewModel

/** Registers this feature's destinations into the app NavGraphBuilder. */
fun NavGraphBuilder.profileSection(navController: NavController, onOpenCompanions: () -> Unit) {
    composable<ProfileRoute> {
        val viewModel = koinViewModel<ProfileViewModel>()
        ProfileScreen(
            viewModel = viewModel,
            onOpenRewatch = { navController.navigate(RewatchRoute) },
            onOpenCompanions = onOpenCompanions,
        )
    }
    composable<RewatchRoute> {
        val viewModel = koinViewModel<RewatchViewModel>()
        RewatchScreen(viewModel, onBack = { navController.popBackStack() })
    }
}
