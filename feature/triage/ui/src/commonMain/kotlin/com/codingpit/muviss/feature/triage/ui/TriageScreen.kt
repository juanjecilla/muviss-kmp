package com.codingpit.muviss.feature.triage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.common.formatEpochDay
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.feature.triage.domain.DeckFilter
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.launch

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
    onOpenSnoozed: () -> Unit,
    onOpenDetail: (MediaId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }
    val deck = rememberSwipeDeckState(animated = state.deckAnimations)
    val scope = rememberCoroutineScope()

    // The single way a verdict is committed, whichever input asked for it: the
    // card flies out first and the deck advances behind it. Buttons and arrow
    // keys used to call the ViewModel directly and cut straight to the next
    // card; routing all three through here is what makes them one gesture.
    val decide: (TriageVerdict, Boolean) -> Unit = { verdict, viaGesture ->
        val top = state.topCard
        if (top != null && verdict in state.verdictsForTopCard) {
            scope.launch { deck.commit(top.id, verdict) { viewModel.onDecide(verdict, viaGesture) } }
        }
    }

    val keyActions = remember(decide) { DeckKeyActions(viewModel::onUndo, viewModel::onSnooze, decide) }

    // Undo plays the exit backwards: the restored card is the one that just
    // left, so it is still parked off screen and only has to come home.
    LaunchedEffect(state.restored?.token) {
        val restored = state.restored ?: return@LaunchedEffect
        // A Snooze carries no verdict and never flew out, so there is nothing
        // to play backwards — the card is simply back on top.
        val verdict = restored.verdict ?: return@LaunchedEffect
        deck.enter(restored.id, directionFor(verdict))
    }

    UndoSnackbarEffect(
        undoable = state.undoable,
        snackbarHostState = snackbarHostState,
        onUndo = viewModel::onUndo,
        onDismissed = viewModel::onUndoDismissed,
    )

    // Left at Material3's Indefinite default on purpose: this is an error with
    // a Retry, and timing it out silently would take away the chance to act.
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
            .onPreviewKeyEvent { event -> handleKey(event.key, event.type, state, keyActions) },
    ) {
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                TriageHeader(
                    onBack = onBack,
                    onOpenSkipped = onOpenSkipped,
                    onOpenSnoozed = onOpenSnoozed,
                    onShowTutorial = viewModel::onShowTutorial,
                )
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

                        else -> DeckArea(
                            state = state,
                            deck = deck,
                            onDecide = { verdict -> decide(verdict, true) },
                            onOpenDetail = onOpenDetail,
                            onSnooze = viewModel::onSnooze,
                            onSnoozeHintDismissed = viewModel::onSnoozeHintDismissed,
                        )
                    }
                }

                // Above the button row, not in Scaffold's bottom-anchored
                // slot: the buttons are the primary, always-available way to
                // decide, and a snackbar sitting on top of them takes that away
                // for as long as it is up.
                SnackbarHost(snackbarHostState)

                if (state.topCard != null) {
                    VerdictButtonRow(
                        verdicts = state.verdictsForTopCard,
                        mediaType = state.topCard?.type ?: MediaType.TV,
                        fourWay = state.controlScheme == TriageControlScheme.FOUR_WAY,
                        onDecide = { verdict -> decide(verdict, false) },
                    )
                }
            }

            if (state.tutorialVisible) {
                TriageTutorial(
                    scheme = state.controlScheme,
                    onDismiss = viewModel::onTutorialDismissed,
                )
            }

            state.snoozeChoiceFor?.let { card ->
                SnoozeChoiceDialog(
                    title = card.title,
                    onChoose = viewModel::onSnoozePeriodChosen,
                    onDismiss = viewModel::onSnoozeSheetDismissed,
                )
            }
        }
    }
}

@Composable
private fun UndoSnackbarEffect(
    undoable: UndoableAction?,
    snackbarHostState: SnackbarHostState,
    onUndo: () -> Unit,
    onDismissed: () -> Unit,
) {
    // The label follows the card that was acted on — a movie's CAUGHT_UP reads
    // "Watched", so the snackbar has to say so too. A Snooze is not a verdict
    // and has no VerdictStyle; it names the date it comes back instead, which
    // is the one thing the user cannot otherwise check before the snackbar goes.
    val label = when (undoable) {
        null -> null
        is UndoableAction.Decision -> styleFor(undoable.verdict, undoable.summary.type, fourWay = true).label
        is UndoableAction.Snooze -> "Snoozed until ${formatEpochDay(undoable.dueAtEpochDay)}"
    }
    LaunchedEffect(undoable) {
        if (undoable == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "$label · ${undoable.summary.title}",
            actionLabel = "Undo",
            // Material3 defaults to Indefinite whenever an action label is
            // given. Long rather than Short because undo is the only safety
            // net for a decision that already committed optimistically.
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo() else onDismissed()
    }
}

/**
 * What each key reaches. Bundled because the keyboard is a parallel path to
 * the same three things every other input has, and passing them one by one put
 * [handleKey] over detekt's parameter budget.
 */
private class DeckKeyActions(
    val onUndo: () -> Unit,
    val onSnooze: () -> Unit,
    val decide: (TriageVerdict, Boolean) -> Unit,
)

/**
 * Arrow keys mirror the drags exactly, `Z` undoes, `S` snoozes. Down is inert under
 * THREE_WAY and for movies, for the same reasons the drag is — the keyboard
 * is a parallel path to the same rules, not a way around them.
 */
private fun handleKey(
    key: Key,
    type: KeyEventType,
    state: TriageUiState,
    keys: DeckKeyActions,
): Boolean {
    val onUndo = keys.onUndo
    val onSnooze = keys.onSnooze
    val decide = keys.decide
    if (type != KeyEventType.KeyDown) return false
    val direction = when (key) {
        Key.DirectionLeft -> DragDirection.LEFT

        Key.DirectionRight -> DragDirection.RIGHT

        Key.DirectionUp -> DragDirection.UP

        Key.DirectionDown -> DragDirection.DOWN

        Key.Z -> {
            onUndo()
            return true
        }

        // The affordance a mouse and a keyboard have instead of a long press.
        Key.S -> {
            onSnooze()
            return true
        }

        else -> return false
    }
    val verdict = verdictFor(direction, state.controlScheme, state.verdictsForTopCard) ?: return false
    decide(verdict, true)
    return true
}

@Composable
private fun TriageHeader(
    onBack: () -> Unit,
    onOpenSkipped: () -> Unit,
    onOpenSnoozed: () -> Unit,
    onShowTutorial: () -> Unit,
) {
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
        TextButton(onClick = onOpenSnoozed) { Text("Snoozed") }
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
private fun DeckArea(
    state: TriageUiState,
    deck: SwipeDeckState,
    onDecide: (TriageVerdict) -> Unit,
    onOpenDetail: (MediaId) -> Unit,
    onSnooze: () -> Unit,
    onSnoozeHintDismissed: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        // Capped so the card stays a card on a wide desktop window rather
        // than stretching across the whole rail layout.
        // Extra room at the top for the stack to rise into, so its rims never
        // reach the filter chips above.
        modifier = Modifier
            .widthIn(max = MAX_CARD_WIDTH)
            .padding(start = MuvissSpacing.l, end = MuvissSpacing.l, bottom = MuvissSpacing.l, top = MuvissSpacing.xxl)
            .fillMaxSize(),
    ) {
        // Back to front, so the deepest card is drawn first and the top card
        // lands on top of all of them.
        state.backingCards.asReversed().forEachIndexed { indexFromBack, backing ->
            val depth = state.backingCards.size - indexFromBack
            // Keyed on the card, so a card keeps its own depth animation as the
            // deck advances underneath it. Unkeyed, these are positional slots
            // whose contents swap and whose depth recomputes instantly — the
            // stack snapped while the top card glided.
            key(backing.id) {
                // graphicsLayer, not layout padding: padding is what made the
                // old peek invisible, and scaling in the layer leaves every
                // card the same *measured* size, which is what lets the next
                // one promote into place without a reflow.
                TriageCardSurface(
                    summary = backing,
                    backing = true,
                    modifier = Modifier.cardDepth(rememberDepth(depth.toFloat(), state.deckAnimations)),
                )
            }
        }
        state.topCard?.let { top ->
            // The card now on top spent the last moment drawn one depth down,
            // so it grows into place from there rather than appearing at full
            // size. A card coming back from undo starts at zero instead: it is
            // arriving from off screen, not from inside the stack.
            val promoted = remember(top.id) { Animatable(if (state.restored?.id == top.id) 0f else 1f) }
            LaunchedEffect(top.id, state.deckAnimations) {
                if (state.deckAnimations) promoted.animateTo(0f, tween(PROMOTE_MS)) else promoted.snapTo(0f)
            }
            // Two layers, because the two motions pivot differently: depth
            // scales from the top edge, the swipe rotates about the centre.
            Box(Modifier.cardDepth(promoted.value).fillMaxSize()) {
                SwipeCard(
                    state = deck,
                    cardId = top.id,
                    scheme = state.controlScheme,
                    available = state.verdictsForTopCard,
                    onDecide = onDecide,
                    onTap = { onOpenDetail(top.id) },
                    onLongPress = onSnooze,
                    // The draggable surface is one thing however many texts it
                    // draws; the poster's own text fallback would otherwise be
                    // indistinguishable from the title beneath it.
                    modifier = Modifier.testTag(TRIAGE_CARD_TAG),
                ) { pending, progress ->
                    TriageCardSurface(summary = top, modifier = Modifier.testTag(TRIAGE_CARD_FACE_TAG))
                    // Above the face and below the drag hint: it must stay
                    // reachable while the card sits still, and disappear under
                    // the hint once a drag is committing to a verdict.
                    SnoozeButton(
                        onSnooze = onSnooze,
                        hintVisible = state.snoozeHintVisible,
                        onHintDismissed = onSnoozeHintDismissed,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                    if (pending != null) {
                        DragHint(
                            verdict = pending,
                            mediaType = top.type,
                            progress = progress,
                            fourWay = state.controlScheme == TriageControlScheme.FOUR_WAY,
                        )
                    }
                }
            }
        }
    }
}

/** A card's place in the stack, in whole depths: 0 is the top card, 1 the one behind it. */
@Composable
private fun rememberDepth(depth: Float, animated: Boolean): Float {
    val value = remember { Animatable(depth) }
    LaunchedEffect(depth, animated) {
        if (animated) value.animateTo(depth, tween(PROMOTE_MS)) else value.snapTo(depth)
    }
    return value.value
}

/**
 * Draws a card at [depth] in the stack: smaller, and risen by the rim that
 * shows above the card in front of it.
 *
 * The origin is the top edge, so scaling shrinks the card upward from a fixed
 * top and the rise below is exactly that rim. Scaling about the centre — the
 * obvious first try — pulls every edge inward, and no offset small enough to
 * look like a deck ever clears the opaque top card: the stack renders and stays
 * invisible.
 */
private fun Modifier.cardDepth(depth: Float): Modifier = graphicsLayer {
    transformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0f)
    val scale = 1f - BACKING_SCALE_STEP * depth
    scaleX = scale
    scaleY = scale
    translationY = -BACKING_OFFSET.toPx() * depth
}

@Composable
private fun TriageCardSurface(summary: MediaSummary, modifier: Modifier = Modifier, backing: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = MuvissSpacing.xs,
        // A card behind the top one is nearly the same value as it — tonal
        // elevation alone leaves the rim invisible in dark theme. The outline
        // is what actually makes the queue legible, in both themes.
        border = if (backing) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
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
private fun DragHint(verdict: TriageVerdict, mediaType: MediaType, progress: Float, fourWay: Boolean) {
    val style = styleFor(verdict, mediaType, fourWay)
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
private fun VerdictButtonRow(
    verdicts: List<TriageVerdict>,
    mediaType: MediaType,
    fourWay: Boolean,
    onDecide: (TriageVerdict) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.xs),
        modifier = Modifier.fillMaxWidth().padding(horizontal = MuvissSpacing.s, vertical = MuvissSpacing.m),
    ) {
        verdicts.forEach { verdict ->
            val style = styleFor(verdict, mediaType, fourWay)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs),
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onDecide(verdict) }
                    .padding(vertical = MuvissSpacing.s)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${style.label}. ${explanationFor(verdict, mediaType)}"
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

/**
 * The snooze affordance (EPIC 42, ADR 0023).
 *
 * A button on the card rather than a fifth column in [VerdictButtonRow]: a
 * Snooze is not a verdict, four labels already overflowed a 1080px phone (see
 * that row's own note), and long press — the obvious touch gesture — does not
 * exist for a mouse. This works on all six targets; the long press and the `S`
 * key are accelerators on top of it.
 */
@Composable
private fun SnoozeButton(
    onSnooze: () -> Unit,
    hintVisible: Boolean,
    onHintDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.padding(MuvissSpacing.xs)) {
        FilledTonalIconButton(
            onClick = onSnooze,
            // 48dp, not the M3 default 40: Android's minimum touch target, and
            // this one sits over a draggable surface where a near miss starts
            // a swipe instead.
            modifier = Modifier.size(SNOOZE_BUTTON_SIZE).testTag(TRIAGE_SNOOZE_TAG),
        ) {
            Icon(MuvissIcons.Snooze, contentDescription = SNOOZE_LABEL)
        }

        if (hintVisible) {
            // A one-shot callout rather than a line in TriageTutorial: that
            // dialog is gated on `triageTutorialSeen`, which is already true on
            // every install that exists, so nobody who has the app today would
            // ever have seen it there.
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, SNOOZE_HINT_OFFSET_PX),
                onDismissRequest = onHintDismissed,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = MuvissSpacing.xs,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = MuvissSpacing.m, vertical = MuvissSpacing.s),
                    ) {
                        Text(
                            text = SNOOZE_HINT,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.widthIn(max = SNOOZE_HINT_MAX_WIDTH),
                        )
                        TextButton(onClick = onHintDismissed) { Text("Got it") }
                    }
                }
            }
        }
    }
}

/**
 * Asks how long to postpone for, under [SnoozePeriod.ASK_EACH_TIME] only.
 *
 * An AlertDialog rather than a bottom sheet, matching `PickerRow`'s choice on
 * the settings screen: the repo has no sheet anywhere, and a dialog is the one
 * modal shape already proven on all six targets.
 */
@Composable
private fun SnoozeChoiceDialog(
    title: String,
    onChoose: (SnoozePeriod) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Ask again about $title") },
        text = {
            Column(modifier = Modifier.testTag(TRIAGE_SNOOZE_SHEET_TAG)) {
                // Only the real durations: ASK_EACH_TIME is the mode that
                // opened this dialog, not something it can offer. A free date
                // picker is deliberately not here yet — see issue for the M3
                // DatePicker's unverified six-target support.
                SnoozePeriod.entries.filter { it.days != null }.forEach { period ->
                    TextButton(onClick = { onChoose(period) }) { Text(period.label) }
                }
            }
        },
    )
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

/**
 * Identifies the top card's face, *inside* [SwipeCard]'s `graphicsLayer`.
 *
 * [TRIAGE_CARD_TAG] is applied to the caller's modifier, which sits outside
 * that layer, so its semantics node's `positionInRoot` never walks through the
 * layer's transform and its bounds do not move when the card is dragged or
 * flung. A node below the layer does move, which is what makes the deck's
 * motion assertable on screen rather than only through `SwipeDeckState`.
 */
const val TRIAGE_CARD_FACE_TAG = "triage-card-face"

/** Identifies the snooze button on the top card (EPIC 42). */
const val TRIAGE_SNOOZE_TAG = "triage-snooze"

/** Identifies the "ask each time" period dialog (EPIC 42). */
const val TRIAGE_SNOOZE_SHEET_TAG = "triage-snooze-sheet"

internal const val SNOOZE_LABEL = "Snooze"

internal const val SNOOZE_HINT = "Not sure? Snooze it and we'll ask again later."

/** Android's minimum touch target; M3's icon button default is 40dp. */
private val SNOOZE_BUTTON_SIZE = 48.dp

/** Clear of the button so the callout points at it rather than covering it. */
private const val SNOOZE_HINT_OFFSET_PX = 56

private val SNOOZE_HINT_MAX_WIDTH = 200.dp

private val MAX_CARD_WIDTH = 420.dp

/**
 * How much each card behind the top one shrinks, and how far its rim rises
 * above it.
 */
private const val BACKING_SCALE_STEP = 0.04f
private val BACKING_OFFSET = 14.dp
private const val HINT_MAX_ALPHA = 0.35f

/** How long a card takes to rise one depth as the deck advances. */
private const val PROMOTE_MS = 220
