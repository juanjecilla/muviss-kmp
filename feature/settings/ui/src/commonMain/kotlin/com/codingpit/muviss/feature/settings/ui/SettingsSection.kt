package com.codingpit.muviss.feature.settings.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/** Registers this feature's destinations into the app NavGraphBuilder. */
fun NavGraphBuilder.settingsSection() {
    composable<SettingsRoute> { SettingsScreen() }
}
