package com.codingpit.muviss.feature.triage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.feature.triage.domain.DeckFilter
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType

/**
 * The triage deck.
 *
 * Three input paths, all equal in what they can express: drag the card, press
 * a labelled button, or use the keyboard. The buttons are the primary,
 * always-available path — the deck must be fully usable without a gesture, on
 * a desktop with a mouse as much as by a screen-reader user on a phone.
 */
@Composable
fun TriageScreen(
    viewModel: TriageViewModel,
    onBack: () -> Unit,
    onOpenSkipped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }

    UndoSnackbarEffect(
        undoable = state.undoable,
        snackbarHostState = snackbarHostState,
        onUndo = viewModel::onUndo,
        onDismissed = viewModel::onUndoDismissed,
    )

    LaunchedEffect(state.failedCommit) {
        val failed = state.failedCommit ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "${failed.summary.title}: ${failed.message}",
            actionLabel = "Retry",
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.onRetryFailedCommit() else viewModel.onFailedCommitDismissed()
    }

    // The deck takes focus as soon as it has something to show, so arrow keys
    // work without a click first — the primary input on desktop and web, and
    // the accessible path everywhere. Keyed on the first card rather than on
    // Unit: on the very first composition the node is not attached yet and
    // requestFocus would throw.
    val hasCard = state.topCard != null
    LaunchedEffect(hasCard) {
        if (!hasCard) return@LaunchedEffect
        // A focus target can only take focus once it has been placed, and
        // effects run before the frame that places it — so wait one frame.
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    // The key handler sits outside the Scaffold: Scaffold lays its content out
    // through a SubcomposeLayout, and a focus target in there is not placed
    // when the effect below runs, so requestFocus would be refused.
    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            // Preview, not bubble: the arrow keys would otherwise be taken by
            // focus traversal before the deck ever sees them.
            .onPreviewKeyEvent { event -> handleKey(event.key, event.type, state, viewModel) },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                TriageHeader(onBack = onBack, onOpenSkipped = onOpenSkipped, onShowTutorial = viewModel::onShowTutorial)
                DeckFilterBar(
                    selectedType = state.filter.type,
                    selectedGenreId = state.filter.genreId,
                    genres = state.genresForFilter,
                    onTypeChange = viewModel::onTypeFilterChange,
                    onGenreChange = viewModel::onGenreFilterChange,
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        state.loading -> CircularProgressIndicator()

                        state.error != null -> ErrorState(state.error.orEmpty(), viewModel::retry)

                        state.topCard == null -> EmptyDeck(
                            filtered = state.filter != DeckFilter(),
                            onClearFilters = viewModel::onClearFilters,
                            onOpenSkipped = onOpenSkipped,
                        )

                        else -> DeckArea(state = state, onDecide = { verdict -> viewModel.onDecide(verdict, viaGesture = true) })
                    }
                }

                if (state.topCard != null) {
                    VerdictButtonRow(
                        verdicts = state.verdictsForTopCard,
                        fourWay = state.controlScheme == TriageControlScheme.FOUR_WAY,
                        onDecide = { verdict -> viewModel.onDecide(verdict, viaGesture = false) },
                    )
                }
            }

            if (state.tutorialVisible) {
                TriageTutorial(
                    scheme = state.controlScheme,
                    onDismiss = viewModel::onTutorialDismissed,
                )
            }
        }
    }
}

@Composable
private fun UndoSnackbarEffect(
    undoable: UndoableDecision?,
    snackbarHostState: SnackbarHostState,
    onUndo: () -> Unit,
    onDismissed: () -> Unit,
) {
    val label = undoable?.let { styleFor(it.verdict, fourWay = true).label }
    LaunchedEffect(undoable) {
        if (undoable == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "$label · ${undoable.summary.title}",
            actionLabel = "Undo",
        )
        if (result == SnackbarResult.ActionPerformed) onUndo() else onDismissed()
    }
}

/**
 * Arrow keys mirror the drags exactly, `Z` undoes. Down is inert under
 * THREE_WAY and for movies, for the same reasons the drag is — the keyboard
 * is a parallel path to the same rules, not a way around them.
 */
private fun handleKey(key: Key, type: KeyEventType, state: TriageUiState, viewModel: TriageViewModel): Boolean {
    if (type != KeyEventType.KeyDown) return false
    val direction = when (key) {
        Key.DirectionLeft -> DragDirection.LEFT

        Key.DirectionRight -> DragDirection.RIGHT

        Key.DirectionUp -> DragDirection.UP

        Key.DirectionDown -> DragDirection.DOWN

        Key.Z -> {
            viewModel.onUndo()
            return true
        }

        else -> return false
    }
    val verdict = verdictFor(direction, state.controlScheme, state.verdictsForTopCard) ?: return false
    viewModel.onDecide(verdict, viaGesture = true)
    return true
}

@Composable
private fun TriageHeader(onBack: () -> Unit, onOpenSkipped: () -> Unit, onShowTutorial: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = MuvissSpacing.s, vertical = MuvissSpacing.xs),
    ) {
        IconButton(onClick = onBack) { Icon(MuvissIcons.Back, contentDescription = "Back") }
        Text(
            text = "Fill your library",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(start = MuvissSpacing.xs),
        )
        TextButton(onClick = onShowTutorial) { Text("How it works") }
        TextButton(onClick = onOpenSkipped) { Text("Skipped") }
    }
}

@Composable
private fun DeckFilterBar(
    selectedType: MediaType?,
    selectedGenreId: String?,
    genres: List<Genre>,
    onTypeChange: (MediaType?) -> Unit,
    onGenreChange: (String?) -> Unit,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
            modifier = Modifier.fillMaxWidth().padding(horizontal = MuvissSpacing.l),
        ) {
            FilterChip(selected = selectedType == null, onClick = { onTypeChange(null) }, label = { Text("All") })
            FilterChip(selected = selectedType == MediaType.MOVIE, onClick = { onTypeChange(MediaType.MOVIE) }, label = { Text("Movies") })
            FilterChip(selected = selectedType == MediaType.TV, onClick = { onTypeChange(MediaType.TV) }, label = { Text("TV") })
        }
        // Genre ids differ between the movie and TV catalogues, so chips only
        // appear once one of the two is picked.
        if (genres.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
                contentPadding = PaddingValues(horizontal = MuvissSpacing.l, vertical = MuvissSpacing.s),
            ) {
                items(genres, key = { it.id }) { genre ->
                    FilterChip(
                        selected = selectedGenreId == genre.id,
                        onClick = { onGenreChange(genre.id.takeIf { it != selectedGenreId }) },
                        label = { Text(genre.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeckArea(state: TriageUiState, onDecide: (TriageVerdict) -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        // Capped so the card stays a card on a wide desktop window rather
        // than stretching across the whole rail layout.
        modifier = Modifier.widthIn(max = MAX_CARD_WIDTH).padding(MuvissSpacing.l).fillMaxSize(),
    ) {
        state.peekedCard?.let { peeked ->
            TriageCardSurface(summary = peeked, modifier = Modifier.padding(top = MuvissSpacing.m))
        }
        state.topCard?.let { top ->
            SwipeCard(
                scheme = state.controlScheme,
                available = state.verdictsForTopCard,
                onDecide = onDecide,
                // The draggable surface is one thing however many texts it
                // draws; the poster's own text fallback would otherwise be
                // indistinguishable from the title beneath it.
                modifier = Modifier.testTag(TRIAGE_CARD_TAG),
            ) { pending, progress ->
                TriageCardSurface(summary = top)
                if (pending != null) DragHint(verdict = pending, progress = progress, fourWay = state.controlScheme == TriageControlScheme.FOUR_WAY)
            }
        }
    }
}

@Composable
private fun TriageCardSurface(summary: MediaSummary, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = MuvissSpacing.xs,
        modifier = modifier.fillMaxSize(),
    ) {
        Column {
            PosterImage(
                url = summary.posterUrl,
                title = summary.title,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Column(Modifier.padding(MuvissSpacing.l)) {
                Text(summary.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    text = listOfNotNull(
                        if (summary.type == MediaType.MOVIE) "Movie" else "TV",
                        summary.year?.toString(),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                summary.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = MuvissSpacing.s),
                    )
                }
            }
        }
    }
}

/**
 * The directional hint: fades in with drag progress, naming the verdict the
 * release would commit, and disappears the moment the drag is let go short of
 * the threshold.
 */
@Composable
private fun DragHint(verdict: TriageVerdict, progress: Float, fourWay: Boolean) {
    val style = styleFor(verdict, fourWay)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(style.color.copy(alpha = HINT_MAX_ALPHA * progress))
            .semantics { contentDescription = "Release to ${style.label}" },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(style.icon, contentDescription = null, tint = style.color)
            Text(style.label, style = MaterialTheme.typography.headlineSmall, color = style.color)
        }
    }
}

/**
 * The primary, always-available way to decide.
 *
 * Icon above label in equal-width columns rather than a row of icon+label
 * buttons: four side-by-side labels do not fit a phone's width, and "Caught
 * up" wrapped to three lines and overflowed the row on a 1080px screen.
 * Equal weights also keep the four targets the same size, so no verdict is
 * harder to hit than its neighbours.
 */
@Composable
private fun VerdictButtonRow(verdicts: List<TriageVerdict>, fourWay: Boolean, onDecide: (TriageVerdict) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.xs),
        modifier = Modifier.fillMaxWidth().padding(horizontal = MuvissSpacing.s, vertical = MuvissSpacing.m),
    ) {
        verdicts.forEach { verdict ->
            val style = styleFor(verdict, fourWay)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs),
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onDecide(verdict) }
                    .padding(vertical = MuvissSpacing.s)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${style.label}. ${explanationFor(verdict)}"
                    },
            ) {
                Icon(style.icon, contentDescription = null, tint = style.color)
                Text(
                    text = style.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = style.color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyDeck(filtered: Boolean, onClearFilters: () -> Unit, onOpenSkipped: () -> Unit) {
    if (filtered) {
        EmptyState(
            icon = MuvissIcons.Filter,
            title = "Nothing left here",
            body = "You've been through everything matching this filter.",
            actionLabel = "Clear filters",
            onAction = onClearFilters,
        )
    } else {
        EmptyState(
            icon = MuvissIcons.CaughtUp,
            title = "All caught up",
            body = "You've triaged everything we can find right now. New titles will show up as they get popular.",
            actionLabel = "Review skipped",
            onAction = onOpenSkipped,
        )
    }
}

/** Identifies the draggable card itself, for tests and for anything that needs to find it. */
const val TRIAGE_CARD_TAG = "triage-card"

private val MAX_CARD_WIDTH = 420.dp
private const val HINT_MAX_ALPHA = 0.35f
