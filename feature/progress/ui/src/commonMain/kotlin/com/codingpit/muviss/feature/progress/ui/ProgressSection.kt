package com.codingpit.muviss.feature.progress.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/** Registers this feature's destinations into the app NavGraphBuilder. */
fun NavGraphBuilder.progressSection() {
    composable<ProgressRoute> { ProgressScreen() }
}
