package com.codingpit.muviss.feature.search.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.codingpit.muviss.core.designsystem.component.CarouselHeader
import com.codingpit.muviss.core.designsystem.component.EpisodeRow
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterCard
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.component.RatingRow
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.search.domain.JUSTWATCH_ATTRIBUTION_TEXT
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
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
    onOpenEpisode: (EpisodeId) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAddToList by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    var tickedEpisode by remember { mutableStateOf<EpisodeId?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // A bulk mark parks its undo in state; this turns it into the snackbar and
    // clears it once acted on, so it can't reappear on the next recomposition.
    LaunchedEffect(state.pendingUndo) {
        val undo = state.pendingUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(undo.message, actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) viewModel.undoBulkMark() else viewModel.dismissUndo()
    }

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
                    DetailExpanded(
                        state,
                        viewModel,
                        onOpenDetail,
                        onAddToList = { showAddToList = true },
                        onEditNote = { editingNote = true },
                        onOpenEpisode = onOpenEpisode,
                        onTapSeenEpisode = { tickedEpisode = it },
                    )
                } else {
                    DetailCompact(
                        state,
                        viewModel,
                        onOpenDetail,
                        onAddToList = { showAddToList = true },
                        onEditNote = { editingNote = true },
                        onOpenEpisode = onOpenEpisode,
                        onTapSeenEpisode = { tickedEpisode = it },
                    )
                }
            }
        }

        // Floating back button over the hero, per the mockups.
        BackButton(onBack, Modifier.statusBarsPadding().padding(12.dp))

        // Detail is a scrolling Box rather than a Scaffold, so it hosts its
        // own snackbar for the bulk-mark undo.
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    tickedEpisode?.let { episodeId ->
        WatchedAgainDialog(
            playCount = state.playCountOf(episodeId),
            onWatchedAgain = {
                viewModel.recordRewatch(episodeId)
                tickedEpisode = null
            },
            onMistake = {
                viewModel.undoLatestPlay(episodeId)
                tickedEpisode = null
            },
            onDismiss = { tickedEpisode = null },
        )
    }

    if (editingNote) {
        NoteEditorSheet(
            initial = state.note.orEmpty(),
            onSave = {
                viewModel.setNote(it)
                editingNote = false
            },
            onDismiss = { editingNote = false },
        )
    }

    val detailsMediaId = state.details?.summary?.id
    if (showAddToList && detailsMediaId != null) {
        val addToListViewModel = koinViewModel<AddToListViewModel> { parametersOf(detailsMediaId) }
        AddToListDialog(addToListViewModel, onDismiss = { showAddToList = false })
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
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
    onEditNote: () -> Unit,
    onOpenEpisode: (EpisodeId) -> Unit,
    onTapSeenEpisode: (EpisodeId) -> Unit,
) {
    val details = state.details!!
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailHero(details)
        Column(
            Modifier.padding(horizontal = MuvissSpacing.l).padding(top = MuvissSpacing.m, bottom = MuvissSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        ) {
            ActionRow(state, viewModel, onAddToList)
            DetailBody(state, viewModel, details, onOpenDetail, onEditNote, onOpenEpisode, onTapSeenEpisode)
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
    onEditNote: () -> Unit,
    onOpenEpisode: (EpisodeId) -> Unit,
    onTapSeenEpisode: (EpisodeId) -> Unit,
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
                DetailBody(state, viewModel, details, onOpenDetail, onEditNote, onOpenEpisode, onTapSeenEpisode)
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
    onEditNote: () -> Unit,
    onOpenEpisode: (EpisodeId) -> Unit,
    onTapSeenEpisode: (EpisodeId) -> Unit,
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
        NoteField(state.note, onEdit = onEditNote)
    }

    details.summary.overview?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    when (details.type) {
        MediaType.MOVIE -> MovieWatchedToggle(
            watched = state.movieWatched,
            playCount = state.moviePlayCount,
            onToggle = {
                val id = EpisodeId.forMovie(details.id)
                if (state.movieWatched) onTapSeenEpisode(id) else viewModel.toggleMovieWatched()
            },
        )

        MediaType.TV -> SeasonsList(
            details = details,
            state = state,
            viewModel = viewModel,
            todayEpochDay = viewModel.todayEpochDay,
            onOpenEpisode = onOpenEpisode,
            onTapSeenEpisode = onTapSeenEpisode,
        )
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
                    size = PosterSize.Grid,
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
        // Labelled, because the poster badge and the star row on this same
        // screen show the *user's* rating out of five — this one is TMDB's
        // public average out of ten.
        details.summary.rating?.let { add("TMDB ${it.toString().take(3)}") }
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

internal const val NOTE_FIELD_TAG = "detailNoteField"
internal const val NOTE_EDITOR_TAG = "detailNoteEditor"
internal const val ADD_NOTE_LABEL = "Add a private note"
internal const val SAVE_NOTE_LABEL = "Save"
internal const val CANCEL_NOTE_LABEL = "Cancel"

/**
 * The personal note (EPIC 15) as one quiet line rather than an always-open
 * text field.
 *
 * A note is optional and usually absent, so an open `OutlinedTextField`
 * between the rating and the overview made the emptiest thing on the screen
 * the loudest. This shows the invitation when there is nothing to show and the
 * note itself — two lines at most — when there is; either way a tap opens
 * [NoteEditorSheet].
 */
@Composable
internal fun NoteField(note: String?, onEdit: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onEdit)
            .testTag(NOTE_FIELD_TAG)
            .padding(vertical = MuvissSpacing.xs),
    ) {
        Icon(
            MuvissIcons.Note,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = note ?: ADD_NOTE_LABEL,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** [NoteEditorContent] in a modal bottom sheet; the sheet itself holds no logic. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorSheet(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        NoteEditorContent(initial = initial, onSave = onSave, onCancel = onDismiss)
    }
}

/**
 * The editor body, separate from the sheet so it can be driven directly in
 * tests. [initial] seeds the draft once — the persisted note cannot change
 * underneath an open editor without the sheet being dismissed first.
 *
 * Saving an empty field is how a note is deleted: collection's `:api`
 * normalizes a blank note to null.
 */
@Composable
internal fun NoteEditorContent(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var draft by remember { mutableStateOf(initial) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = MuvissSpacing.l).padding(bottom = MuvissSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
    ) {
        Text("Your note", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text("Private to you — nobody else sees this.") },
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag(NOTE_EDITOR_TAG),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(CANCEL_NOTE_LABEL) }
            Button(onClick = { onSave(draft) }) { Text(SAVE_NOTE_LABEL) }
        }
    }
}

@Composable
private fun MovieWatchedToggle(watched: Boolean, playCount: Int, onToggle: () -> Unit) {
    Row(Modifier.clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = watched, onCheckedChange = { onToggle() })
        Text(
            when {
                !watched -> "Mark as watched"
                playCount > 1 -> "Watched · $playCount×"
                else -> "Watched"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SeasonsList(
    details: MediaDetails,
    state: DetailUiState,
    viewModel: DetailViewModel,
    todayEpochDay: Long,
    onOpenEpisode: (EpisodeId) -> Unit,
    onTapSeenEpisode: (EpisodeId) -> Unit,
) {
    var confirmMarkShow by rememberSaveable { mutableStateOf(false) }

    // Seeded once from where the user left off, then owned by the user: a
    // reseed on every progress change would slam a season shut mid-tick.
    val expanded = rememberSaveable(details.id.toString(), saver = expandedSeasonsSaver) {
        mutableStateOf(
            SeasonExpansion.initiallyExpandedIndex(details.seasons, state.seenEpisodes, todayEpochDay)
                ?.let { setOf(details.seasons[it].number) }
                .orEmpty(),
        )
    }

    SeasonsHeader(details.seasons.size)
    MarkShowSeenButton(onClick = { confirmMarkShow = true })

    details.seasons.forEachIndexed { index, season ->
        SeasonSection(
            season = season,
            state = state,
            todayEpochDay = todayEpochDay,
            expanded = season.number in expanded.value,
            onExpandedChange = { open ->
                expanded.value = if (open) expanded.value + season.number else expanded.value - season.number
            },
            actions = SeasonActions(
                onMarkSeasonSeen = {
                    viewModel.markSeasonSeen(season)
                    // Finished seasons get out of the way — except the last,
                    // which is where the next episode will land.
                    if (SeasonExpansion.collapsesAfterMarking(index, details.seasons.size)) {
                        expanded.value = expanded.value - season.number
                    }
                },
                onUnmarkSeason = { viewModel.unmarkSeason(season) },
                onTapEpisode = { episodeId ->
                    if (state.isSeen(episodeId)) onTapSeenEpisode(episodeId) else viewModel.toggleEpisodeSeen(episodeId)
                },
                onCatchUp = viewModel::markPreviousSeen,
                onOpenEpisode = onOpenEpisode,
            ),
        )
    }

    if (confirmMarkShow) {
        MarkShowSeenDialog(
            onConfirm = {
                viewModel.markShowSeen()
                confirmMarkShow = false
            },
            onDismiss = { confirmMarkShow = false },
        )
    }
}

/** Season numbers survive rotation; a `Set<Int>` needs a saver of its own. */
private val expandedSeasonsSaver = listSaver<MutableState<Set<Int>>, Int>(
    save = { it.value.toList() },
    restore = { mutableStateOf(it.toSet()) },
)

/** Bundled so [SeasonSection] can be driven in tests without a ViewModel. */
internal class SeasonActions(
    val onMarkSeasonSeen: () -> Unit,
    val onUnmarkSeason: () -> Unit,
    val onTapEpisode: (EpisodeId) -> Unit,
    val onCatchUp: (EpisodeId) -> Unit,
    val onOpenEpisode: (EpisodeId) -> Unit,
)

internal const val MARK_SHOW_SEEN_LABEL = "Mark whole show as seen"

@Composable
private fun MarkShowSeenButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(MARK_SHOW_TAG)) {
        Icon(MuvissIcons.CaughtUp, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(MARK_SHOW_SEEN_LABEL, modifier = Modifier.padding(start = MuvissSpacing.s))
    }
}

internal const val MARK_SHOW_TAG = "detailMarkShowSeen"

/**
 * Confirmation for the one action here that touches every season at once.
 * Reversible, but reversing it episode by episode would be miserable, so it
 * asks first.
 */
@Composable
private fun MarkShowSeenDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark whole show as seen?") },
        text = { Text("Every episode that has aired will be marked watched. Episodes that haven't aired yet are left alone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Mark seen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(CANCEL_NOTE_LABEL) } },
    )
}

/**
 * The count is `seasons.size` — the seasons actually rendered below — rather
 * than TMDB's `number_of_seasons`, so the header can never disagree with the
 * rows under it. `TmdbProvider` drops season 0 ("Specials"), and the two
 * numbers differ for any show that has them.
 */
@Composable
internal fun SeasonsHeader(seasonCount: Int) {
    Text(
        "Seasons ($seasonCount)",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = MuvissSpacing.s),
    )
}

internal const val SEASON_HEADER_TAG_PREFIX = "seasonHeader"
internal const val SEASON_TOGGLE_TAG_PREFIX = "seasonToggle"
internal const val SEASON_EPISODES_TAG_PREFIX = "seasonEpisodes"

/**
 * One collapsible season.
 *
 * The header carries everything needed to decide whether to open it — name,
 * aired progress, a progress bar — plus the toggle that marks the season
 * seen. Counts are stated against *aired* episodes, not total: "12 / 12
 * aired" is a truthful "caught up" for a season still airing, where "12 / 22"
 * would read as unfinished forever.
 */
@Composable
internal fun SeasonSection(
    season: Season,
    state: DetailUiState,
    todayEpochDay: Long,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    actions: SeasonActions,
) {
    val aired = season.episodes.filter { it.hasAiredBy(todayEpochDay) }
    val seenAired = aired.count { state.isSeen(it.id) }
    val allAiredSeen = aired.isNotEmpty() && seenAired == aired.size
    val nextUpId = aired.firstOrNull { !state.isSeen(it.id) }?.id

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { onExpandedChange(!expanded) }
                .testTag("$SEASON_HEADER_TAG_PREFIX${season.number}")
                .padding(vertical = MuvissSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MuvissIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 0f else -90f),
            )
            Text(
                season.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(start = MuvissSpacing.xs),
            )
            Text(
                "$seenAired / ${aired.size} aired",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            // A season with nothing aired has nothing to mark, so the toggle
            // would be a control that silently does nothing.
            Checkbox(
                checked = allAiredSeen,
                enabled = aired.isNotEmpty(),
                onCheckedChange = { checked -> if (checked) actions.onMarkSeasonSeen() else actions.onUnmarkSeason() },
                modifier = Modifier.testTag("$SEASON_TOGGLE_TAG_PREFIX${season.number}"),
            )
        }
        LinearProgressIndicator(
            progress = { if (aired.isEmpty()) 0f else seenAired / aired.size.toFloat() },
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
        )
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.testTag("$SEASON_EPISODES_TAG_PREFIX${season.number}"),
                verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs),
            ) {
                season.episodes.forEach { episode ->
                    SeasonEpisodeRow(episode, state, todayEpochDay, nextUpId, actions)
                }
            }
        }
    }
}

@Composable
private fun SeasonEpisodeRow(
    episode: Episode,
    state: DetailUiState,
    todayEpochDay: Long,
    nextUpId: EpisodeId?,
    actions: SeasonActions,
) {
    val isSeen = state.isSeen(episode.id)
    val hasAired = episode.hasAiredBy(todayEpochDay)
    val playCount = state.playCountOf(episode.id)
    val subtitle = buildString {
        append("S${episode.seasonNumber} · E${episode.episodeNumber}")
        if (playCount > 1) append(" · watched $playCount×")
        if (!hasAired) append(" · not aired yet")
    }
    // Catch-up only makes sense for something already out, and only ahead of
    // where you are.
    val offerCatchUp = hasAired && !isSeen && episode.id != nextUpId
    EpisodeRow(
        title = episode.name,
        subtitle = subtitle,
        seen = isSeen,
        // An unaired episode cannot be ticked: doing so pushes seen past
        // aired, which WatchProgress forbids and the library would throw on.
        onToggle = { if (hasAired) actions.onTapEpisode(episode.id) },
        stillUrl = episode.stillUrl,
        nextUp = episode.id == nextUpId,
        onClick = { actions.onOpenEpisode(episode.id) },
        secondaryActionLabel = if (offerCatchUp) "Catch up" else null,
        onSecondaryAction = if (offerCatchUp) {
            { actions.onCatchUp(episode.id) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun Episode.hasAiredBy(todayEpochDay: Long): Boolean {
    val airDate = airDateEpochDay
    return airDate != null && airDate <= todayEpochDay
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

internal const val WATCHED_AGAIN_LABEL = "I watched it again"
internal const val TICK_MISTAKE_LABEL = "I ticked it by mistake"

/**
 * What tapping an already-ticked episode means. It used to mean "un-tick",
 * which made a second viewing unrecordable and turned a mis-tap and a rewatch
 * into the same gesture.
 *
 * "By mistake" drops the newest viewing only, so an episode watched three
 * times goes to two rather than losing the history behind the slip; clearing
 * it outright lives in episode detail, where it can be deliberate.
 */
@Composable
private fun WatchedAgainDialog(
    playCount: Int,
    onWatchedAgain: () -> Unit,
    onMistake: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("You've already watched this") },
        text = {
            Text(
                if (playCount > 1) {
                    "Watched $playCount× so far. Did you watch it again, or was the last tick a mistake?"
                } else {
                    "Did you watch it again, or was that tick a mistake?"
                },
            )
        },
        confirmButton = { TextButton(onClick = onWatchedAgain) { Text(WATCHED_AGAIN_LABEL) } },
        dismissButton = { TextButton(onClick = onMistake) { Text(TICK_MISTAKE_LABEL) } },
    )
}
