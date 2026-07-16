package com.codingpit.muviss.feature.search.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.codingpit.muviss.models.MediaId
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Registers the search + detail destinations into the app NavGraphBuilder. */
fun NavGraphBuilder.searchSection(navController: NavController) {
    composable<SearchRoute> {
        val viewModel = koinViewModel<SearchViewModel>()
        SearchScreen(viewModel) { id ->
            navController.navigate(DetailRoute(id.toString()))
        }
    }
    composable<DetailRoute> { entry ->
        val route = entry.toRoute<DetailRoute>()
        val id = MediaId.parse(route.mediaId)
        val viewModel = koinViewModel<DetailViewModel> { parametersOf(id) }
        DetailScreen(
            viewModel,
            onBack = { navController.popBackStack() },
            // "More like this" (EPIC 16) pushes another DetailRoute on top of
            // this one — Navigation Compose supports recursive routes of the
            // same type natively, so tapping through several titles just
            // grows the back stack and Back unwinds it one title at a time.
            onOpenDetail = { newId -> navController.navigate(DetailRoute(newId.toString())) },
        )
    }
}
