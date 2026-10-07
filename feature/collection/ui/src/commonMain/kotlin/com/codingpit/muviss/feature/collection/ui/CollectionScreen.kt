package com.codingpit.muviss.feature.collection.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterCard
import com.codingpit.muviss.core.designsystem.component.SegmentedSwitch
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.collection.domain.CollectionEntry
import com.codingpit.muviss.feature.collection.domain.MediaList
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus

/** The two segments the Collection tab switches between (EPIC 17 adds [LISTS] alongside the original library view) — mirrors Progress's Watch Next/Upcoming switch. */
private enum class CollectionSegment {
    LIBRARY,
    LISTS,
}

private fun CollectionSegment.label(): String = when (this) {
    CollectionSegment.LIBRARY -> "Library"
    CollectionSegment.LISTS -> "Lists"
}

/**
 * The Collection tab's root: a segmented switch between the saved-title
 * "Library" grid and user-defined "Lists" (EPIC 17), rather than a sixth
 * bottom-nav destination or a sixth tab mixed in with the status filters
 * below it — lists aren't a status slice of the library, they're a
 * different grouping entirely.
 */
@Composable
fun CollectionScreen(
    viewModel: CollectionViewModel,
    listsViewModel: ListsViewModel,
    onOpenDetail: (MediaId) -> Unit,
    onOpenList: (MediaList) -> Unit,
    onOpenSearch: () -> Unit = {},
) {
    var segment by remember { mutableStateOf(CollectionSegment.LIBRARY) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MuvissSpacing.l, vertical = MuvissSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Library", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            if (segment == CollectionSegment.LIBRARY) {
                TypeFilterMenuButton(state.typeFilter, onSelect = viewModel::selectTypeFilter)
                SortMenuButton(state.sort, onSelect = viewModel::selectSort)
            }
        }
        SegmentedSwitch(
            options = CollectionSegment.entries.map { it.label() },
            selectedIndex = CollectionSegment.entries.indexOf(segment),
            onSelect = { segment = CollectionSegment.entries[it] },
            modifier = Modifier.padding(horizontal = MuvissSpacing.l),
        )
        when (segment) {
            CollectionSegment.LIBRARY -> LibraryScreen(viewModel, onOpenDetail, onOpenSearch)
            CollectionSegment.LISTS -> ListsScreen(listsViewModel, onOpenList)
        }
    }
}

/**
 * Movies / TV / both, as an icon + menu beside the sort control it mirrors.
 *
 * A menu rather than a second visible control: the Library already spends a
 * title row and a segmented switch before the first poster, and a status chip
 * row after it. Another always-on selector would push the grid a further
 * ~48dp down a screen that is already short on room.
 */
@Composable
private fun TypeFilterMenuButton(selected: CollectionTypeFilter, onSelect: (CollectionTypeFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                MuvissIcons.Filter,
                contentDescription = "Filter by type (${selected.label()})",
                tint = if (selected == CollectionTypeFilter.ALL) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CollectionTypeFilter.entries.forEach { typeFilter ->
                DropdownMenuItem(
                    text = { Text(typeFilter.label()) },
                    leadingIcon = {
                        if (typeFilter == selected) {
                            Icon(MuvissIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = {
                        onSelect(typeFilter)
                        open = false
                    },
                )
            }
        }
    }
}

/** Sort control moved into the header per the redesign — icon button + menu. */
@Composable
private fun SortMenuButton(selected: CollectionSort, onSelect: (CollectionSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(MuvissIcons.Sort, contentDescription = "Sort (${selected.label()})", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CollectionSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.label()) },
                    leadingIcon = {
                        if (sort == selected) {
                            Icon(MuvissIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = {
                        onSelect(sort)
                        open = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    viewModel: CollectionViewModel,
    onOpenDetail: (MediaId) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // A refresh failure used to vanish silently, leaving a stale library and
    // no explanation; the ViewModel now parks a one-shot message here.
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            CollectionFilterChips(state, onSelect = viewModel::selectFilter)

            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    when {
                        state.loading -> CircularProgressIndicator(Modifier.padding(top = MuvissSpacing.xxl))
                        state.error != null -> ErrorState(state.error!!, onRetry = { viewModel.refresh() })
                        state.visibleEntries.isEmpty() -> EmptyLibraryState(state.filter, state.typeFilter, onOpenSearch)
                        else -> CollectionGrid(state.visibleEntries, onOpenDetail)
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun EmptyLibraryState(filter: CollectionFilter, typeFilter: CollectionTypeFilter, onOpenSearch: () -> Unit) {
    when {
        // An active type filter is the likelier reason the grid is empty, and
        // it lives behind a menu — so say so rather than implying the library
        // itself is bare.
        typeFilter != CollectionTypeFilter.ALL -> EmptyState(
            icon = MuvissIcons.Filter,
            title = "No ${typeFilter.label().lowercase()} here",
            body = "Nothing in this tab matches the type filter.",
        )

        filter == CollectionFilter.FAVORITES -> EmptyState(
            icon = MuvissIcons.FavoriteOutline,
            title = "No favorites yet",
            body = "Tap the heart on a saved title to add one.",
        )

        // The first screen a new user is likely to open. Say what to do and
        // give them the way there (EPIC 30, #73).
        else -> EmptyState(
            icon = MuvissIcons.Library,
            title = "Nothing here yet",
            body = "Search for a movie or show and add it to your library.",
            actionLabel = FIND_SOMETHING_LABEL,
            onAction = onOpenSearch,
        )
    }
}

internal const val FIND_SOMETHING_LABEL = "Find something to watch"

/** Status filter chips with count suffixes ("Watching 12"); Favorites keeps its star. */
@Composable
private fun CollectionFilterChips(
    state: CollectionUiState,
    onSelect: (CollectionFilter) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MuvissSpacing.l, vertical = MuvissSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
    ) {
        CollectionFilter.entries.forEach { filter ->
            val count = state.count(filter)
            FilterChip(
                selected = filter == state.filter,
                onClick = { onSelect(filter) },
                label = { Text(if (count > 0) "${filter.label()} $count" else filter.label()) },
            )
        }
    }
}

@Composable
private fun CollectionGrid(
    entries: List<CollectionEntry>,
    onOpenDetail: (MediaId) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        contentPadding = PaddingValues(
            start = MuvissSpacing.m,
            end = MuvissSpacing.m,
            top = MuvissSpacing.m,
            bottom = MuvissSpacing.bottomContent,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.mediaId.toString() }) { entry ->
            PosterCard(
                title = entry.title,
                posterUrl = entry.posterUrl,
                onClick = { onOpenDetail(entry.mediaId) },
                rating = entry.rating,
                progress = entry.watchingProgress(),
            )
        }
    }
}

/** Sage progress strip only while actively watching — watched/finished posters stay clean. */
private fun CollectionEntry.watchingProgress(): Float? = if (status == WatchStatus.WATCHING && airedEpisodes > 0) seenEpisodes / airedEpisodes.toFloat() else null

private fun CollectionFilter.label(): String = when (this) {
    CollectionFilter.NOT_STARTED -> "Not started"
    CollectionFilter.WATCHING -> "Watching"
    CollectionFilter.WATCHED -> "Watched"
    CollectionFilter.FINISHED -> "Finished"
    CollectionFilter.FAVORITES -> "★ Favorites"
}

private fun CollectionTypeFilter.label(): String = when (this) {
    CollectionTypeFilter.ALL -> "All"
    CollectionTypeFilter.MOVIES -> "Movies"
    CollectionTypeFilter.TV -> "TV shows"
}

private fun CollectionSort.label(): String = when (this) {
    CollectionSort.RECENTLY_ADDED -> "Recently added"
    CollectionSort.RATING -> "Rating"
    CollectionSort.TITLE -> "Title"
}
