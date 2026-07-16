package com.codingpit.muviss.feature.settings.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import org.koin.compose.viewmodel.koinViewModel

/** Registers this feature's destinations into the app NavGraphBuilder. */
fun NavGraphBuilder.settingsSection(navController: NavController) {
    composable<SettingsRoute> {
        val viewModel = koinViewModel<SettingsViewModel>()
        SettingsScreen(
            viewModel,
            onOpenLicenses = { navController.navigate(LicensesRoute) },
            onOpenImport = { navController.navigate(ImportRoute) },
        )
    }
    composable<LicensesRoute> {
        LicensesScreen()
    }
    composable<ImportRoute> {
        val viewModel = koinViewModel<ImportViewModel>()
        ImportScreen(viewModel, onDone = { navController.popBackStack() })
    }
}
