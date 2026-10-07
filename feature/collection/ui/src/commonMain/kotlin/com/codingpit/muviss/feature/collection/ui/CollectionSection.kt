package com.codingpit.muviss.feature.collection.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.codingpit.muviss.models.MediaId
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Registers this feature's destinations into the app NavGraphBuilder.
 * [onOpenDetail] lets the app shell route taps into the search feature's
 * detail screen without collection depending on search directly (cross-
 * feature deps go through `:api` only, and detail navigation isn't part of
 * collection's own contract). [navController] drives collection's own
 * internal push (into a list's contents, EPIC 17), the same way
 * search:ui's `searchSection` drives its own `DetailRoute` pushes.
 */
fun NavGraphBuilder.collectionSection(navController: NavController, onOpenDetail: (MediaId) -> Unit, onOpenSearch: () -> Unit = {}) {
    composable<CollectionRoute> {
        val viewModel = koinViewModel<CollectionViewModel>()
        val listsViewModel = koinViewModel<ListsViewModel>()
        CollectionScreen(
            viewModel,
            listsViewModel,
            onOpenDetail = onOpenDetail,
            onOpenList = { list -> navController.navigate(ListContentsRoute(list.id, list.name)) },
            onOpenSearch = onOpenSearch,
        )
    }
    composable<ListContentsRoute> { entry ->
        val route = entry.toRoute<ListContentsRoute>()
        val viewModel = koinViewModel<ListContentsViewModel> { parametersOf(route.listId) }
        ListContentsScreen(
            name = route.name,
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
            onOpenDetail = onOpenDetail,
        )
    }
}
