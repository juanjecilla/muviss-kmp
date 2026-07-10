package com.codingpit.muviss.feature.collection.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.feature.collection.domain.CollectionEntry
import com.codingpit.muviss.models.MediaId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    viewModel: CollectionViewModel,
    onOpenDetail: (MediaId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        CollectionFilterTabs(state.filter, onSelect = viewModel::selectFilter)

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
    }
}

private fun CollectionFilter.label(): String = when (this) {
    CollectionFilter.NOT_STARTED -> "Not started"
    CollectionFilter.WATCHING -> "Watching"
    CollectionFilter.WATCHED -> "Watched"
    CollectionFilter.FINISHED -> "Finished"
    CollectionFilter.FAVORITES -> "Favorites"
}
