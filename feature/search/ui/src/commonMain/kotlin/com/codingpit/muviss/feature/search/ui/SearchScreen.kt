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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.CarouselHeader
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterCard
import com.codingpit.muviss.core.designsystem.component.PosterSkeleton
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.text.resolve
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.back_to_discover
import com.codingpit.muviss.feature.search.ui.generated.resources.carousel_for_you
import com.codingpit.muviss.feature.search.ui.generated.resources.carousel_popular_movies
import com.codingpit.muviss.feature.search.ui.generated.resources.carousel_popular_tv
import com.codingpit.muviss.feature.search.ui.generated.resources.genre_empty_body
import com.codingpit.muviss.feature.search.ui.generated.resources.genre_empty_title
import com.codingpit.muviss.feature.search.ui.generated.resources.genres_movies
import com.codingpit.muviss.feature.search.ui.generated.resources.genres_tv
import com.codingpit.muviss.feature.search.ui.generated.resources.intro_dismiss
import com.codingpit.muviss.feature.search.ui.generated.resources.intro_find
import com.codingpit.muviss.feature.search.ui.generated.resources.intro_tick
import com.codingpit.muviss.feature.search.ui.generated.resources.intro_title
import com.codingpit.muviss.feature.search.ui.generated.resources.intro_triage
import com.codingpit.muviss.feature.search.ui.generated.resources.search_clear
import com.codingpit.muviss.feature.search.ui.generated.resources.search_empty_body
import com.codingpit.muviss.feature.search.ui.generated.resources.search_empty_title
import com.codingpit.muviss.feature.search.ui.generated.resources.search_placeholder
import com.codingpit.muviss.feature.search.ui.generated.resources.triage_entry_body
import com.codingpit.muviss.feature.search.ui.generated.resources.triage_entry_title
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import org.jetbrains.compose.resources.stringResource

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenDetail: (MediaId) -> Unit,
    onOpenTriage: () -> Unit,
    introVisible: Boolean = false,
    onIntroDismissed: () -> Unit = {},
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
                ErrorState(state.error!!.resolve(), viewModel::retry)
            }

            else -> when (state.mode) {
                SearchMode.DISCOVER -> DiscoverBrowse(state, onSelectGenre = viewModel::selectGenre, onOpenDetail = onOpenDetail, onOpenTriage = onOpenTriage, intro = DiscoverIntro(introVisible, onIntroDismissed))

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
            val placeholder = stringResource(Res.string.search_placeholder)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    // Drawn only; the field below carries it as its label.
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    // A bare BasicTextField has no label: TalkBack announced
                    // only "Edit box" (EPIC 31b, #164).
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        MuvissIcons.Close,
                        contentDescription = stringResource(Res.string.search_clear),
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
    intro: DiscoverIntro = DiscoverIntro(),
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = MuvissSpacing.bottomContent),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xl),
    ) {
        // First run only (EPIC 30, #73): what the app is for, before anything else.
        if (intro.visible) DiscoverIntroCard(onDismiss = intro.onDismiss)
        // Triage's main entry point (ADR 0010). It leads the browse because
        // an empty or thin library is exactly the state it exists to fix.
        TriageEntryCard(onOpenTriage)
        // "For you" (EPIC 16) leads the browse when it has anything to show;
        // MediaCarousel itself renders nothing while state.forYou is empty
        // (no library signal yet), so no separate visibility check is needed.
        MediaCarousel(stringResource(Res.string.carousel_for_you), state.forYou, onOpenDetail)
        GenreChipRow(stringResource(Res.string.genres_movies), state.movieGenres) { onSelectGenre(it, MediaType.MOVIE) }
        GenreChipRow(stringResource(Res.string.genres_tv), state.tvGenres) { onSelectGenre(it, MediaType.TV) }
        MediaCarousel(stringResource(Res.string.carousel_popular_movies), state.popularMovies, onOpenDetail)
        MediaCarousel(stringResource(Res.string.carousel_popular_tv), state.popularTv, onOpenDetail)
    }
}

/** Whether the first-run intro shows, and what dismissing it does. Bundled so DiscoverBrowse stays under detekt's parameter budget. */
private class DiscoverIntro(val visible: Boolean = false, val onDismiss: () -> Unit = {})

/**
 * The one-time Discover intro (EPIC 30, #73): the app's three moves, once, on
 * a fresh install. Three lines rather than a pager — the tabs are right
 * there, and a carousel is one more thing to swipe past.
 */
@Composable
private fun DiscoverIntroCard(onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().testTag(DISCOVER_INTRO_TAG),
    ) {
        Column(Modifier.padding(MuvissSpacing.l), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
            Text(stringResource(Res.string.intro_title), style = MaterialTheme.typography.titleMedium)
            IntroLine(MuvissIcons.Search, stringResource(Res.string.intro_find))
            IntroLine(MuvissIcons.WatchNext, stringResource(Res.string.intro_tick))
            IntroLine(MuvissIcons.CaughtUp, stringResource(Res.string.intro_triage))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.intro_dismiss)) }
            }
        }
    }
}

@Composable
private fun IntroLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = MuvissSpacing.m))
    }
}

internal const val DISCOVER_INTRO_TAG = "discover-intro"

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
                    stringResource(Res.string.triage_entry_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    stringResource(Res.string.triage_entry_body),
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
            TextButton(onClick = onClear) { Text(stringResource(Res.string.back_to_discover)) }
        }
        if (state.genreResults.isEmpty()) {
            EmptyState(
                icon = MuvissIcons.SearchOff,
                title = stringResource(Res.string.genre_empty_title),
                body = stringResource(Res.string.genre_empty_body),
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
            title = stringResource(Res.string.search_empty_title),
            body = stringResource(Res.string.search_empty_body),
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
