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
        DetailScreen(viewModel, onBack = { navController.popBackStack() })
    }
}
