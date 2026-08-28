package com.codingpit.muviss.feature.search.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Registers the search + detail destinations into the app NavGraphBuilder. */
fun NavGraphBuilder.searchSection(navController: NavController, onOpenTriage: () -> Unit) {
    composable<SearchRoute> {
        val viewModel = koinViewModel<SearchViewModel>()
        SearchScreen(
            viewModel = viewModel,
            onOpenDetail = { id -> navController.navigate(DetailRoute(id.toString())) },
            // Triage's route lives in a peer feature's :ui (ADR 0004), so the
            // app shell owns the navigation and passes it down.
            onOpenTriage = onOpenTriage,
        )
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
            onOpenEpisode = { episodeId -> navController.navigate(EpisodeDetailRoute(episodeId.toString())) },
        )
    }
    composable<EpisodeDetailRoute> { entry ->
        val episodeId = EpisodeId.parse(entry.toRoute<EpisodeDetailRoute>().episodeId)
        val viewModel = koinViewModel<EpisodeDetailViewModel> { parametersOf(episodeId) }
        EpisodeDetailScreen(viewModel, onBack = { navController.popBackStack() })
    }
}
