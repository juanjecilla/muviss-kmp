package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.codingpit.muviss.core.designsystem.component.PosterImage
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onOpenDetail: (MediaId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAddToList by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.details?.summary?.title ?: "Details") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (state.details != null) {
                        TextButton(onClick = viewModel::toggleFavorite) {
                            Text(if (state.favorite) "★ Favorite" else "☆ Favorite")
                        }
                        // Per-show new-episode notification mute (EPIC 5); only meaningful once saved.
                        if (state.saved) {
                            TextButton(onClick = viewModel::toggleNotificationsMuted) {
                                Text(if (state.notificationsMuted) "🔕 Muted" else "🔔 Notify")
                            }
                        }
                        // Lists (EPIC 17) are orthogonal to library membership, so this
                        // is always available, saved or not.
                        TextButton(onClick = { showAddToList = true }) { Text("+ List") }
                        TextButton(onClick = viewModel::toggleSaved) {
                            Text(if (state.saved) "Remove" else "Add to Library")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopStart) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(24.dp))

                state.error != null -> Column(Modifier.padding(24.dp)) {
                    Text(state.error!!)
                    TextButton(onClick = viewModel::load) { Text("Retry") }
                }

                state.details != null -> DetailContent(state, viewModel, onOpenDetail)
            }
        }
    }

    val detailsMediaId = state.details?.summary?.id
    if (showAddToList && detailsMediaId != null) {
        val addToListViewModel = koinViewModel<AddToListViewModel> { parametersOf(detailsMediaId) }
        AddToListDialog(addToListViewModel, onDismiss = { showAddToList = false })
    }
}

@Composable
private fun DetailContent(state: DetailUiState, viewModel: DetailViewModel, onOpenDetail: (MediaId) -> Unit) {
    val details = state.details!!
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp) {
                PosterImage(
                    url = details.summary.posterUrl,
                    title = details.summary.title,
                    modifier = Modifier.width(120.dp).height(180.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(details.summary.title, style = MaterialTheme.typography.titleLarge)
                details.summary.year?.let { Text("$it", style = MaterialTheme.typography.bodyMedium) }
                if (details.genres.isNotEmpty()) {
                    Text(details.genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                details.summary.rating?.let {
                    Text("★ ${it.toString().take(3)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        details.summary.overview?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        // Rating + note (EPIC 15) only make sense once the title is saved —
        // consistent with the mute button above, which is the other
        // membership-gated affordance on this screen.
        if (state.saved) {
            RatingRow(state.rating, onRate = viewModel::setRating, onClear = viewModel::clearRating)
            NoteEditor(state.note, onSave = viewModel::setNote)
        }

        when (details.type) {
            MediaType.MOVIE -> MovieWatchedToggle(state.movieWatched, onToggle = viewModel::toggleMovieWatched)
            MediaType.TV -> SeasonsList(details, state, viewModel)
        }

        state.watchProviders?.let { providers -> WhereToWatchSection(providers) }

        MoreLikeThisSection(state.moreLikeThis, onOpenDetail)
    }
}

/** "More like this" row (EPIC 16): recommendations, or similar titles when the Detail screen's viewmodel found no recommendations. Hidden when both are empty. */
@Composable
private fun MoreLikeThisSection(items: List<MediaSummary>, onOpenDetail: (MediaId) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("More like this", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id.toString() }) { item ->
                Box(Modifier.width(110.dp)) { MoreLikeThisCard(item) { onOpenDetail(item.id) } }
            }
        }
    }
}

@Composable
private fun MoreLikeThisCard(item: MediaSummary, onClick: () -> Unit) {
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
    }
}

/** Streaming/rent/buy rows for the configured region; the caller only renders this when [providers] has data. */
@Composable
private fun WhereToWatchSection(providers: WatchProviders) {
    if (providers.isEmpty) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Where to watch", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        ProviderRow("Stream", providers.flatrate)
        ProviderRow("Rent", providers.rent)
        ProviderRow("Buy", providers.buy)
        // TMDB's terms require this attribution wherever JustWatch-sourced
        // provider data renders — do not remove without checking ADR 0001.
        Text(JUSTWATCH_ATTRIBUTION_TEXT, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ProviderRow(label: String, providers: List<WatchProvider>) {
    if (providers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(providers, key = { it.id }) { provider -> ProviderLogo(provider) }
        }
    }
}

@Composable
private fun ProviderLogo(provider: WatchProvider) {
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp) {
        if (provider.logoUrl != null) {
            AsyncImage(
                model = provider.logoUrl,
                contentDescription = provider.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Text(provider.name.take(2), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * A 1-10 star row for the personal rating (EPIC 15): tapping a star sets the
 * rating up to and including it; tapping the currently-set value again
 * clears it (handled by [DetailViewModel.setRating]). Plain clickable
 * [Text] rather than a slider/rating-bar component — no new dependency, and
 * ten discrete taps is precise enough for a 1-10 scale.
 */
@Composable
private fun RatingRow(rating: Int?, onRate: (Int) -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Your rating", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (value in 1..10) {
                Text(
                    text = if (rating != null && value <= rating) "★" else "☆",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable { onRate(value) },
                )
            }
        }
        if (rating != null) {
            TextButton(onClick = onClear) { Text("Clear rating ($rating/10)") }
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

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Your note", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text("Add a private note…") },
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
    Text("Seasons", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    details.seasons.forEach { season ->
        SeasonSection(season, state, viewModel)
    }
}

@Composable
private fun SeasonSection(season: Season, state: DetailUiState, viewModel: DetailViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${season.name} · ${state.seenCountIn(season)}/${season.episodes.size}", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { viewModel.markSeasonSeen(season) }) { Text("Mark season seen") }
        }
        val progress = if (season.episodes.isEmpty()) 0f else state.seenCountIn(season) / season.episodes.size.toFloat()
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        season.episodes.forEach { episode ->
            EpisodeRow(
                name = episode.name,
                number = episode.episodeNumber,
                seen = state.isSeen(episode.id),
                onToggleSeen = { viewModel.toggleEpisodeSeen(episode.id) },
                onMarkPrevious = { viewModel.markPreviousSeen(episode.id) },
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    name: String,
    number: Int,
    seen: Boolean,
    onToggleSeen: () -> Unit,
    onMarkPrevious: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = seen, onCheckedChange = { onToggleSeen() })
        Text(
            "$number. $name",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 4.dp),
        )
        if (!seen) {
            Button(onClick = onMarkPrevious) { Text("Caught up to here") }
        }
    }
}
