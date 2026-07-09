package com.codingpit.muviss.feature.collection.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.codingpit.muviss.models.MediaId
import org.koin.compose.viewmodel.koinViewModel

/**
 * Registers this feature's destination into the app NavGraphBuilder.
 * [onOpenDetail] lets the app shell route taps into the search feature's
 * detail screen without collection depending on search directly (cross-
 * feature deps go through `:api` only, and detail navigation isn't part of
 * collection's own contract).
 */
fun NavGraphBuilder.collectionSection(onOpenDetail: (MediaId) -> Unit) {
    composable<CollectionRoute> {
        val viewModel = koinViewModel<CollectionViewModel>()
        CollectionScreen(viewModel, onOpenDetail)
    }
}
