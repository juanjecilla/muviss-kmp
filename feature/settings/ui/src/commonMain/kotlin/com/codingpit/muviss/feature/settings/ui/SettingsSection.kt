package com.codingpit.muviss.feature.settings.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import org.koin.compose.viewmodel.koinViewModel

/** Registers this feature's destinations into the app NavGraphBuilder. */
fun NavGraphBuilder.settingsSection(
    navController: NavController,
    onOpenTriage: () -> Unit,
    // Onboarding's route belongs to a peer feature's :ui (ADR 0004), so the shell makes the hop.
    onReplayIntroduction: () -> Unit = {},
) {
    composable<SettingsRoute> {
        val viewModel = koinViewModel<SettingsViewModel>()
        SettingsScreen(
            viewModel,
            onOpenLicenses = { navController.navigate(LicensesRoute) },
            onOpenImport = { navController.navigate(ImportRoute) },
            // Triage's route belongs to a peer feature's :ui, which settings
            // must not depend on (ADR 0004) — the app shell owns both graphs
            // and hands the navigation down, same as progressSection does.
            onOpenMutedGenres = { navController.navigate(MutedGenresRoute) },
            onOpenTriage = onOpenTriage,
            onReplayIntroduction = onReplayIntroduction,
            tmdbKey = koinViewModel<TmdbKeyViewModel>(),
        )
    }
    composable<LicensesRoute> {
        LicensesScreen()
    }
    composable<MutedGenresRoute> {
        MutedGenresScreen(koinViewModel<MutedGenresViewModel>())
    }
    composable<ImportRoute> {
        val viewModel = koinViewModel<ImportViewModel>()
        ImportScreen(viewModel, onDone = { navController.popBackStack() })
    }
}
