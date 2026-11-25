package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.ImageKind
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.PersonId
import com.codingpit.muviss.models.TitleImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Registers the search + detail destinations into the app NavGraphBuilder. [refreshRequests] is how the desktop
 * View menu's Ctrl/Cmd+R reaches Discover (#965); the app shell owns the menu, so it passes the flow down.
 */
fun NavGraphBuilder.searchSection(
    navController: NavController,
    onOpenTriage: () -> Unit,
    onOpenNextPick: (anchor: MediaId?) -> Unit,
    refreshRequests: Flow<Unit> = emptyFlow(),
) {
    composable<SearchRoute> {
        val viewModel = koinViewModel<SearchViewModel>()
        val intro = koinViewModel<DiscoverIntroViewModel>()
        val introVisible by intro.visible.collectAsStateWithLifecycle()
        SearchScreen(
            viewModel = viewModel,
            introVisible = introVisible,
            onIntroDismissed = intro::dismiss,
            refreshRequests = refreshRequests,
            onOpenDetail = { id -> navController.navigate(DetailRoute(id.toString())) },
            // Triage's route lives in a peer feature's :ui (ADR 0004), so the
            // app shell owns the navigation and passes it down.
            onOpenTriage = onOpenTriage,
            // The Pick Session (EPIC 51, #545) is in :feature:nextpick, which this module may not depend on.
            // Discover opens it with no anchor on purpose: no title here was just finished (#664).
            onOpenNextPick = { onOpenNextPick(null) },
        )
    }
    composable<DetailRoute> { entry ->
        val route = entry.toRoute<DetailRoute>()
        // A malformed id can arrive from outside the app (a hand-written `muviss://title/…` link, a stale
        // notification). Parsing it here used to throw inside composition, which aborts the whole app on
        // iOS (#863). Pop back instead, the same as any other dead end.
        val id = runCatching { MediaId.parse(route.mediaId) }.getOrNull()
        if (id == null) {
            LaunchedEffect(route.mediaId) { navController.popBackStack() }
            return@composable
        }
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
            onOpenImage = { kind, url -> navController.navigate(ImageViewerRoute(id.toString(), kind.name, url)) },
            onOpenPerson = { personId -> navController.navigate(PersonRoute(personId.toString())) },
            nudge = koinViewModel<NotificationNudgeViewModel> { parametersOf(id) },
            // A finished title's entry opens the session anchored on that title (#664).
            onOpenNextPick = onOpenNextPick,
            linkLanguage = koinInject<MetadataLocale>().language,
        )
    }
    composable<EpisodeDetailRoute> { entry ->
        val episodeId = EpisodeId.parse(entry.toRoute<EpisodeDetailRoute>().episodeId)
        val viewModel = koinViewModel<EpisodeDetailViewModel> { parametersOf(episodeId) }
        EpisodeDetailScreen(
            viewModel,
            onBack = { navController.popBackStack() },
            onOpenImage = { kind, url -> navController.navigate(ImageViewerRoute(episodeId.show.toString(), kind.name, url, episodeId.toString())) },
            onOpenPerson = { personId -> navController.navigate(PersonRoute(personId.toString())) },
        )
    }
    composable<ImageViewerRoute> { entry ->
        val route = entry.toRoute<ImageViewerRoute>()
        val viewModel = koinViewModel<ImageViewerViewModel> {
            parametersOf(ImageViewerArgs(MediaId.parse(route.mediaId), route.episodeId?.let(EpisodeId::parse), ImageKind.valueOf(route.kind), TitleImage(route.seedUrl)))
        }
        ImageViewerScreen(viewModel, onClose = { navController.popBackStack() })
    }
    composable<PersonRoute> { entry ->
        val personId = PersonId.parse(entry.toRoute<PersonRoute>().personId)
        val viewModel = koinViewModel<PersonViewModel> { parametersOf(personId) }
        PersonScreen(
            viewModel,
            onBack = { navController.popBackStack() },
            // Same recursion as "More like this": a title opens Detail, a person on that opens another page, Back unwinds it.
            onOpenDetail = { id -> navController.navigate(DetailRoute(id.toString())) },
            linkLanguage = koinInject<MetadataLocale>().language,
        )
    }
}
