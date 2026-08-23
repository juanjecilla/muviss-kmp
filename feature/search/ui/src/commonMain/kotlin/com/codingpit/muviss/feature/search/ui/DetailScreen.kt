package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.codingpit.muviss.core.designsystem.component.CarouselHeader
import com.codingpit.muviss.core.designsystem.component.EpisodeRow
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterCard
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.RatingRow
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.search.domain.JUSTWATCH_ATTRIBUTION_TEXT
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchProvider
import com.codingpit.muviss.models.WatchProviders
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Width at which Detail switches to the two-pane expanded layout. */
private val EXPANDED_BREAKPOINT = 840.dp

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onOpenDetail: (MediaId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAddToList by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> CircularProgressIndicator(
                Modifier.align(Alignment.Center).padding(MuvissSpacing.xl),
            )

            state.error != null -> ErrorState(
                message = state.error!!,
                onRetry = viewModel::load,
                modifier = Modifier.align(Alignment.Center),
            )

            state.details != null -> BoxWithConstraints {
                if (maxWidth >= EXPANDED_BREAKPOINT) {
                    DetailExpanded(state, viewModel, onOpenDetail, onAddToList = { showAddToList = true })
                } else {
                    DetailCompact(state, viewModel, onOpenDetail, onAddToList = { showAddToList = true })
                }
            }
        }

        // Floating back button over the hero, per the mockups.
        BackButton(onBack, Modifier.statusBarsPadding().padding(12.dp))
    }

    val detailsMediaId = state.details?.summary?.id
    if (showAddToList && detailsMediaId != null) {
        val addToListViewModel = koinViewModel<AddToListViewModel> { parametersOf(detailsMediaId) }
        AddToListDialog(addToListViewModel, onDismiss = { showAddToList = false })
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
        modifier = modifier.size(36.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(MuvissIcons.Back, contentDescription = "Back", tint = Color.White)
        }
    }
}

/** Compact (phone): backdrop hero with the poster overlapping into content. */
@Composable
private fun DetailCompact(
    state: DetailUiState,
    viewModel: DetailViewModel,
    onOpenDetail: (MediaId) -> Unit,
    onAddToList: () -> Unit,
) {
    val details = state.details!!
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailHero(details)
        Column(
            Modifier.padding(horizontal = MuvissSpacing.l).padding(top = MuvissSpacing.m, bottom = MuvissSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        ) {
            ActionRow(state, viewModel, onAddToList)
            DetailBody(state, viewModel, details, onOpenDetail)
        }
    }
}

/** Expanded (≥840dp): poster + actions pinned left, content right. */
@Composable
private fun DetailExpanded(
    state: DetailUiState,
    viewModel: DetailViewModel,
    onOpenDetail: (MediaId) -> Unit,
    onAddToList: () -> Unit,
) {
    val details = state.details!!
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailBackdrop(details, height = 200.dp)
        Row(
            Modifier.padding(horizontal = MuvissSpacing.xxl).offset(y = (-56).dp),
            horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.xl),
        ) {
            Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.m)) {
                Surface(shape = MaterialTheme.shapes.medium, shadowElevation = 8.dp) {
                    PosterImage(
                        url = details.summary.posterUrl,
                        title = details.summary.title,
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium),
                    )
                }
                ActionRow(state, viewModel, onAddToList, stacked = true)
            }
            Column(
                Modifier.weight(1f).padding(top = 64.dp).widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
            ) {
                Text(details.summary.title, style = MaterialTheme.typography.headlineMedium)
                MetadataLine(details)
                DetailBody(state, viewModel, details, onOpenDetail)
            }
        }
    }
}

/** Everything below the hero/actions, shared by both layouts. */
@Composable
private fun DetailBody(
    state: DetailUiState,
    viewModel: DetailViewModel,
    details: MediaDetails,
    onOpenDetail: (MediaId) -> Unit,
) {
    // A skipped title (ADR 0010) leaves nothing in the library, so this line
    // is the only way back to it once the deck's undo snackbar has gone.
    if (state.skipped) SkippedBanner(onUndo = viewModel::unskip)

    // Rating + note (EPIC 15) only make sense once the title is saved —
    // consistent with the mute button, the other membership-gated affordance.
    if (state.saved) {
        Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
            Text("Your rating", style = MaterialTheme.typography.titleSmall)
            RatingRow(state.rating, onRate = viewModel::setRating, onClear = viewModel::clearRating)
        }
        NoteEditor(state.note, onSave = viewModel::setNote)
    }

    details.summary.overview?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    when (details.type) {
        MediaType.MOVIE -> MovieWatchedToggle(state.movieWatched, onToggle = viewModel::toggleMovieWatched)
        MediaType.TV -> SeasonsList(details, state, viewModel)
    }

    state.watchProviders?.let { providers -> WhereToWatchSection(providers) }

    MoreLikeThisSection(state.moreLikeThis, onOpenDetail)
}

/** 16:9 backdrop with a gradient into the background color. */
@Composable
private fun DetailBackdrop(details: MediaDetails, height: androidx.compose.ui.unit.Dp) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().height(height)) {
        if (details.backdropUrl != null) {
            AsyncImage(
                model = details.backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow))
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to background)),
        )
    }
}

/** Compact hero: backdrop + poster overlapping down into the content. */
@Composable
private fun DetailHero(details: MediaDetails) {
    Box(Modifier.fillMaxWidth()) {
        DetailBackdrop(details, height = 200.dp)
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = MuvissSpacing.l)
                .offset(y = 30.dp),
            horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
            verticalAlignment = Alignment.Bottom,
        ) {
            Surface(shape = MaterialTheme.shapes.small, shadowElevation = 8.dp) {
                PosterImage(
                    url = details.summary.posterUrl,
                    title = details.summary.title,
                    modifier = Modifier.width(72.dp).height(108.dp).clip(MaterialTheme.shapes.small),
                )
            }
            Column(Modifier.padding(bottom = 6.dp, end = MuvissSpacing.l)) {
                Text(details.summary.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                MetadataLine(details)
            }
        }
    }
    // Room for the poster overhang before the action row starts.
    Spacer(Modifier.height(34.dp))
}

@Composable
private fun MetadataLine(details: MediaDetails) {
    val parts = buildList {
        details.summary.year?.let { add(it.toString()) }
        add(if (details.type == MediaType.TV) "TV" else "Movie")
        if (details.genres.isNotEmpty()) add(details.genres.take(2).joinToString(", "))
        details.summary.rating?.let { add("★ ${it.toString().take(3)}") }
    }
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Primary actions: amber add-to-library pill + round outlined icon buttons
 * for favorite / notification mute / add-to-list (replaces the old emoji
 * text buttons). [stacked] lays the pill above the icons (expanded left rail).
 */
@Composable
private fun ActionRow(
    state: DetailUiState,
    viewModel: DetailViewModel,
    onAddToList: () -> Unit,
    stacked: Boolean = false,
) {
    val pill: @Composable () -> Unit = {
        Button(
            onClick = viewModel::toggleSaved,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(),
            modifier = if (stacked) Modifier.fillMaxWidth() else Modifier,
        ) {
            Text(if (state.saved) "In Library ✓" else "Add to Library")
        }
    }
    val icons: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
            OutlinedIconButton(onClick = viewModel::toggleFavorite) {
                Icon(
                    if (state.favorite) MuvissIcons.Favorite else MuvissIcons.FavoriteOutline,
                    contentDescription = if (state.favorite) "Remove from favorites" else "Add to favorites",
                    tint = if (state.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Per-show new-episode notification mute (EPIC 5); only meaningful once saved.
            if (state.saved) {
                OutlinedIconButton(onClick = viewModel::toggleNotificationsMuted) {
                    Icon(
                        if (state.notificationsMuted) MuvissIcons.BellOff else MuvissIcons.Bell,
                        contentDescription = if (state.notificationsMuted) "Unmute new-episode notifications" else "Mute new-episode notifications",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Lists (EPIC 17) are orthogonal to library membership — always available.
            OutlinedIconButton(onClick = onAddToList) {
                Icon(
                    MuvissIcons.AddToList,
                    contentDescription = "Add to list",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
            pill()
            icons()
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { pill() }
            icons()
        }
    }
}

/** "More like this" row (EPIC 16): recommendations, or similar titles as fallback. Hidden when empty. */
@Composable
private fun MoreLikeThisSection(items: List<MediaSummary>, onOpenDetail: (MediaId) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
        CarouselHeader("More like this", modifier = Modifier.padding(top = MuvissSpacing.s))
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

/** Streaming/rent/buy rows for the configured region; the caller only renders this when [providers] has data. */
@Composable
private fun WhereToWatchSection(providers: WatchProviders) {
    if (providers.isEmpty) return
    Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
        Text("Where to watch", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = MuvissSpacing.s))
        ProviderRow("Stream", providers.flatrate)
        ProviderRow("Rent", providers.rent)
        ProviderRow("Buy", providers.buy)
        // TMDB's terms require this attribution wherever JustWatch-sourced
        // provider data renders — do not remove without checking ADR 0001.
        Text(
            JUSTWATCH_ATTRIBUTION_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderRow(label: String, providers: List<WatchProvider>) {
    if (providers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
            items(providers, key = { it.id }) { provider -> ProviderLogo(provider) }
        }
    }
}

@Composable
private fun ProviderLogo(provider: WatchProvider) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        if (provider.logoUrl != null) {
            AsyncImage(
                model = provider.logoUrl,
                contentDescription = provider.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small),
            )
        } else {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Text(provider.name.take(2), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * An always-visible, expandable text field for the personal note (EPIC 15) —
 * simpler than a dialog for free text this short. Local [draft] tracks
 * in-progress edits; [LaunchedEffect] resyncs it whenever the persisted
 * [note] changes from elsewhere (e.g. the value just loaded).
 */
@Composable
private fun NoteEditor(note: String?, onSave: (String) -> Unit) {
    var draft by remember { mutableStateOf(note.orEmpty()) }
    LaunchedEffect(note) { draft = note.orEmpty() }

    Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
        Text("Your note", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text("Add a private note…") },
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft != note.orEmpty()) {
            TextButton(onClick = { onSave(draft) }) { Text("Save note") }
        }
    }
}

@Composable
private fun MovieWatchedToggle(watched: Boolean, onToggle: () -> Unit) {
    Row(Modifier.clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = watched, onCheckedChange = { onToggle() })
        Text(if (watched) "Watched" else "Mark as watched", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SeasonsList(details: MediaDetails, state: DetailUiState, viewModel: DetailViewModel) {
    Text("Seasons", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = MuvissSpacing.s))
    details.seasons.forEach { season ->
        SeasonSection(season, state, viewModel)
    }
}

@Composable
private fun SeasonSection(season: Season, state: DetailUiState, viewModel: DetailViewModel) {
    val seen = state.seenCountIn(season)
    val total = season.episodes.size
    // First unseen episode is "next up" — amber left rule in its row.
    val nextUpId = season.episodes.firstOrNull { !state.isSeen(it.id) }?.id
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(season.name, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$seen / $total watched",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                if (seen < total) {
                    TextButton(onClick = { viewModel.markSeasonSeen(season) }) { Text("Mark season seen") }
                }
            }
        }
        val progress = if (total == 0) 0f else seen / total.toFloat()
        LinearProgressIndicator(
            progress = { progress },
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
        )
        season.episodes.forEach { episode ->
            val isSeen = state.isSeen(episode.id)
            EpisodeRow(
                title = episode.name,
                subtitle = "S${episode.seasonNumber} · E${episode.episodeNumber}",
                seen = isSeen,
                onToggle = { viewModel.toggleEpisodeSeen(episode.id) },
                stillUrl = episode.stillUrl,
                nextUp = episode.id == nextUpId,
                secondaryActionLabel = if (!isSeen && episode.id != nextUpId) "Catch up" else null,
                onSecondaryAction = if (!isSeen && episode.id != nextUpId) {
                    { viewModel.markPreviousSeen(episode.id) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SkippedBanner(onUndo: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = MuvissSpacing.m, vertical = MuvissSpacing.xs),
        ) {
            Icon(MuvissIcons.Skip, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "You skipped this during triage",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = MuvissSpacing.s),
            )
            TextButton(onClick = onUndo) { Text("Undo") }
        }
    }
}
