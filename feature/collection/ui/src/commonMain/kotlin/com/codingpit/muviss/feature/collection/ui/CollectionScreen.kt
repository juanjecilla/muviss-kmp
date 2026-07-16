package com.codingpit.muviss.feature.collection.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.feature.collection.domain.CollectionEntry
import com.codingpit.muviss.feature.collection.domain.MediaList
import com.codingpit.muviss.models.MediaId

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    viewModel: CollectionViewModel,
    listsViewModel: ListsViewModel,
    onOpenDetail: (MediaId) -> Unit,
    onOpenList: (MediaList) -> Unit,
) {
    var segment by remember { mutableStateOf(CollectionSegment.LIBRARY) }

    Column(Modifier.fillMaxSize()) {
        CollectionSegmentTabs(segment, onSelect = { segment = it })
        when (segment) {
            CollectionSegment.LIBRARY -> LibraryScreen(viewModel, onOpenDetail)
            CollectionSegment.LISTS -> ListsScreen(listsViewModel, onOpenList)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionSegmentTabs(selected: CollectionSegment, onSelect: (CollectionSegment) -> Unit) {
    val segments = CollectionSegment.entries
    PrimaryScrollableTabRow(selectedTabIndex = segments.indexOf(selected)) {
        segments.forEach { segment ->
            Tab(
                selected = segment == selected,
                onClick = { onSelect(segment) },
                text = { Text(segment.label()) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    viewModel: CollectionViewModel,
    onOpenDetail: (MediaId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        CollectionFilterTabs(state.filter, onSelect = viewModel::selectFilter)
        CollectionSortRow(state.sort, onSelect = viewModel::selectSort)

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.padding(top = 32.dp))
                    state.error != null -> ErrorState(state.error!!, onRetry = viewModel::refresh)
                    state.visibleEntries.isEmpty() -> EmptyLibraryState(state.filter)
                    else -> CollectionGrid(state.visibleEntries, onOpenDetail)
                }
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun EmptyLibraryState(filter: CollectionFilter) {
    val message = when (filter) {
        CollectionFilter.FAVORITES -> "No favorites yet — tap the star on a saved title to add one."
        else -> "Nothing here yet — search for a movie or show and add it to your library."
    }
    Text(
        message,
        modifier = Modifier.padding(top = 32.dp, start = 24.dp, end = 24.dp),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun CollectionFilterTabs(
    selected: CollectionFilter,
    onSelect: (CollectionFilter) -> Unit,
) {
    val filters = CollectionFilter.entries
    PrimaryScrollableTabRow(selectedTabIndex = filters.indexOf(selected)) {
        filters.forEach { filter ->
            Tab(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                text = { Text(filter.label()) },
            )
        }
    }
}

/** Minimal sort control (EPIC 15): a row of labels, the selected one bold — no dropdown/menu dependency needed for three options. */
@Composable
private fun CollectionSortRow(
    selected: CollectionSort,
    onSelect: (CollectionSort) -> Unit,
) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sort:", style = MaterialTheme.typography.labelMedium)
        CollectionSort.entries.forEach { sort ->
            TextButton(onClick = { onSelect(sort) }) {
                Text(
                    sort.label(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (sort == selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(12.dp),
    ) {
        items(entries, key = { it.mediaId.toString() }) { entry ->
            CollectionCard(entry) { onOpenDetail(entry.mediaId) }
        }
    }
}

@Composable
private fun CollectionCard(entry: CollectionEntry, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
        ) {
            PosterImage(
                url = entry.posterUrl,
                title = entry.title,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)),
            )
        }
        Text(
            entry.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (entry.favorite) {
            Text("★ Favorite", style = MaterialTheme.typography.labelSmall)
        }
        entry.rating?.let { rating ->
            Text("★ $rating/10", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun CollectionFilter.label(): String = when (this) {
    CollectionFilter.NOT_STARTED -> "Not started"
    CollectionFilter.WATCHING -> "Watching"
    CollectionFilter.WATCHED -> "Watched"
    CollectionFilter.FINISHED -> "Finished"
    CollectionFilter.FAVORITES -> "Favorites"
}

private fun CollectionSort.label(): String = when (this) {
    CollectionSort.RECENTLY_ADDED -> "Recently added"
    CollectionSort.RATING -> "Rating"
    CollectionSort.TITLE -> "Title"
}
