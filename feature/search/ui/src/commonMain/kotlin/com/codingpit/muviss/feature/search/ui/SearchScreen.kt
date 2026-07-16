package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenDetail: (MediaId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            label = { Text("Search movies & TV") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                CircularProgressIndicator(Modifier.padding(top = 32.dp))
            }

            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                ErrorState(state.error!!, viewModel::retry)
            }

            else -> when (state.mode) {
                SearchMode.DISCOVER -> DiscoverBrowse(state, onSelectGenre = viewModel::selectGenre, onOpenDetail = onOpenDetail)

                SearchMode.GENRE_BROWSE -> GenreResults(
                    state,
                    onClear = viewModel::clearGenre,
                    onLoadMore = viewModel::loadMore,
                    onOpenDetail = onOpenDetail,
                )

                SearchMode.SEARCH_RESULTS -> SearchResults(state, onLoadMore = viewModel::loadMore, onOpenDetail = onOpenDetail)
            }
        }
    }
}

@Composable
private fun DiscoverBrowse(
    state: SearchUiState,
    onSelectGenre: (Genre, MediaType) -> Unit,
    onOpenDetail: (MediaId) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // "For you" (EPIC 16) leads the browse when it has anything to show;
        // MediaCarousel itself renders nothing while state.forYou is empty
        // (no library signal yet), so no separate visibility check is needed.
        MediaCarousel("For you", state.forYou, onOpenDetail)
        GenreChipRow("Movie genres", state.movieGenres) { onSelectGenre(it, MediaType.MOVIE) }
        GenreChipRow("TV genres", state.tvGenres) { onSelectGenre(it, MediaType.TV) }
        MediaCarousel("Popular movies", state.popularMovies, onOpenDetail)
        MediaCarousel("Popular TV", state.popularTv, onOpenDetail)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreChipRow(title: String, genres: List<Genre>, onClick: (Genre) -> Unit) {
    if (genres.isEmpty()) return
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(genres, key = { it.id }) { genre ->
                SuggestionChip(onClick = { onClick(genre) }, label = { Text(genre.name) })
            }
        }
    }
}

@Composable
private fun MediaCarousel(title: String, items: List<MediaSummary>, onOpenDetail: (MediaId) -> Unit) {
    if (items.isEmpty()) return
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id.toString() }) { item ->
                Box(Modifier.width(110.dp)) { MediaCard(item) { onOpenDetail(item.id) } }
            }
        }
    }
}

@Composable
private fun GenreResults(
    state: SearchUiState,
    onClear: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenDetail: (MediaId) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.selectedGenre?.name.orEmpty(), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onClear) { Text("Back to discover") }
        }
        if (state.genreResults.isEmpty()) {
            Text(
                "No titles found",
                modifier = Modifier.padding(top = 32.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            PagedResultsGrid(state.genreResults, state.loadingMore, onLoadMore, onOpenDetail)
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchUiState,
    onLoadMore: () -> Unit,
    onOpenDetail: (MediaId) -> Unit,
) {
    if (state.results.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Text(
                "No results",
                modifier = Modifier.padding(top = 32.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        PagedResultsGrid(state.results, state.loadingMore, onLoadMore, onOpenDetail)
    }
}

/** A results grid that asks [onLoadMore] for the next page once the user nears the end (infinite scroll). */
@Composable
private fun PagedResultsGrid(
    results: List<MediaSummary>,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenDetail: (MediaId) -> Unit,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                val total = gridState.layoutInfo.totalItemsCount
                if (lastVisibleIndex != null && total > 0 && lastVisibleIndex >= total - LOAD_MORE_THRESHOLD) {
                    onLoadMore()
                }
            }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 110.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(results, key = { it.id.toString() }) { item ->
            MediaCard(item) { onOpenDetail(item.id) }
        }
        if (loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun MediaCard(item: MediaSummary, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
        ) {
            PosterImage(
                url = item.posterUrl,
                title = item.title,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)),
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        item.year?.let {
            Text("$it", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

private const val LOAD_MORE_THRESHOLD = 6
