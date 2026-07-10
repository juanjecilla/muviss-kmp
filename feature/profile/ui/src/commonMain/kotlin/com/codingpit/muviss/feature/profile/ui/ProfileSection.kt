package com.codingpit.muviss.feature.profile.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import org.koin.compose.viewmodel.koinViewModel

/** Registers this feature's destinations into the app NavGraphBuilder. */
fun NavGraphBuilder.profileSection() {
    composable<ProfileRoute> {
        val viewModel = koinViewModel<ProfileViewModel>()
        ProfileScreen(viewModel)
    }
}
