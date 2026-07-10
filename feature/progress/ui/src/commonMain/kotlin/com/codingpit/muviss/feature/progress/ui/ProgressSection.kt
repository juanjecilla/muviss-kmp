package com.codingpit.muviss.feature.progress.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.codingpit.muviss.models.MediaId
import org.koin.compose.viewmodel.koinViewModel

/**
 * Registers this feature's destination into the app NavGraphBuilder.
 * [onOpenDetail] lets the app shell route taps into search's detail screen
 * without progress depending on search directly (cross-feature deps go
 * through `:api` only, and detail navigation isn't part of progress's own
 * contract) — mirrors collection's `collectionSection`.
 */
fun NavGraphBuilder.progressSection(onOpenDetail: (MediaId) -> Unit) {
    composable<ProgressRoute> {
        val viewModel = koinViewModel<ProgressViewModel>()
        ProgressScreen(viewModel, onOpenDetail)
    }
}
