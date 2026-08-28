package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.CarouselHeader
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterCard
import com.codingpit.muviss.core.designsystem.component.PosterSkeleton
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenDetail: (MediaId) -> Unit,
    onOpenTriage: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = MuvissSpacing.l)) {
        SearchPill(
            query = state.query,
            onQueryChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(vertical = MuvissSpacing.m),
        )

        when {
            state.loading -> LoadingSkeletonGrid()

            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                ErrorState(state.error!!, viewModel::retry)
            }

            else -> when (state.mode) {
                SearchMode.DISCOVER -> DiscoverBrowse(state, onSelectGenre = viewModel::selectGenre, onOpenDetail = onOpenDetail, onOpenTriage = onOpenTriage)

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

/**
 * The raised search pill from the design doc — a custom Surface rather than
 * M3's `SearchBar` on purpose: that component is still churning in the
 * material3 alpha and brings a full-screen expanding overlay this screen
 * doesn't want (results render inline below the pill instead).
 */
@Composable
private fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
            modifier = Modifier.padding(horizontal = MuvissSpacing.l, vertical = MuvissSpacing.m),
        ) {
            Icon(
                MuvissIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "Search movies & TV",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        MuvissIcons.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Poster-shaped shimmer placeholders while the initial content loads. */
@Composable
private fun LoadingSkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(12) { PosterSkeleton() }
    }
}

@Composable
private fun DiscoverBrowse(
    state: SearchUiState,
    onSelectGenre: (Genre, MediaType) -> Unit,
    onOpenDetail: (MediaId) -> Unit,
    onOpenTriage: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = MuvissSpacing.bottomContent),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xl),
    ) {
        // Triage's main entry point (ADR 0010). It leads the browse because
        // an empty or thin library is exactly the state it exists to fix.
        TriageEntryCard(onOpenTriage)
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

/** The way into the triage deck. Not a bottom-bar tab — five is the ceiling. */
@Composable
private fun TriageEntryCard(onOpenTriage: () -> Unit) {
    Surface(
        onClick = onOpenTriage,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(MuvissSpacing.l),
        ) {
            Icon(MuvissIcons.CaughtUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f).padding(horizontal = MuvissSpacing.m)) {
                Text(
                    "Fill your library",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "Sort through titles one at a time — skip, save for later, or mark yourself caught up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Icon(MuvissIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun GenreChipRow(title: String, genres: List<Genre>, onClick: (Genre) -> Unit) {
    if (genres.isEmpty()) return
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = MuvissSpacing.s),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
            items(genres, key = { it.id }) { genre ->
                SuggestionChip(
                    onClick = { onClick(genre) },
                    label = { Text(genre.name) },
                    shape = CircleShape,
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MediaCarousel(title: String, items: List<MediaSummary>, onOpenDetail: (MediaId) -> Unit) {
    if (items.isEmpty()) return
    Column {
        CarouselHeader(title, modifier = Modifier.padding(bottom = MuvissSpacing.s))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m)) {
            items(items, key = { it.id.toString() }) { item ->
                PosterCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    onClick = { onOpenDetail(item.id) },
                    modifier = Modifier.width(110.dp),
                )
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
            Modifier.fillMaxWidth().padding(bottom = MuvissSpacing.s),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.selectedGenre?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClear) { Text("Back to discover") }
        }
        if (state.genreResults.isEmpty()) {
            EmptyState(
                icon = MuvissIcons.SearchOff,
                title = "No titles found",
                body = "Try another genre.",
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
        EmptyState(
            icon = MuvissIcons.SearchOff,
            title = "No results",
            body = "Check the spelling or try a different title.",
        )
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
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        contentPadding = PaddingValues(bottom = MuvissSpacing.bottomContent),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(results, key = { it.id.toString() }) { item ->
            PosterCard(
                title = item.title,
                posterUrl = item.posterUrl,
                onClick = { onOpenDetail(item.id) },
            )
        }
        if (loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = MuvissSpacing.m), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
    }
}

private const val LOAD_MORE_THRESHOLD = 6
