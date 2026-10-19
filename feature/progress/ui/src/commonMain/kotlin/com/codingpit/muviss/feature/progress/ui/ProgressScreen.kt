package com.codingpit.muviss.feature.progress.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.CelebrationBurst
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.component.SegmentedSwitch
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.text.resolveAsync
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.feature.progress.ui.generated.resources.Res
import com.codingpit.muviss.feature.progress.ui.generated.resources.action_undo
import com.codingpit.muviss.feature.progress.ui.generated.resources.cowatch_entry
import com.codingpit.muviss.feature.progress.ui.generated.resources.mark_watched_description
import com.codingpit.muviss.feature.progress.ui.generated.resources.next_episode_of
import com.codingpit.muviss.feature.progress.ui.generated.resources.progress_title
import com.codingpit.muviss.feature.progress.ui.generated.resources.tab_upcoming
import com.codingpit.muviss.feature.progress.ui.generated.resources.tab_watch_next
import com.codingpit.muviss.feature.progress.ui.generated.resources.tick_row_confirmation
import com.codingpit.muviss.feature.progress.ui.generated.resources.tick_snackbar
import com.codingpit.muviss.feature.progress.ui.generated.resources.tick_snackbar_caught_up
import com.codingpit.muviss.feature.progress.ui.generated.resources.tick_snackbar_season_done
import com.codingpit.muviss.feature.progress.ui.generated.resources.watch_next_caught_up
import com.codingpit.muviss.feature.progress.ui.generated.resources.watch_next_empty_body
import com.codingpit.muviss.feature.progress.ui.generated.resources.watch_next_empty_title
import com.codingpit.muviss.feature.progress.ui.generated.resources.watch_next_episode
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** The two segments the Progress tab switches between (EPIC 14 adds [UPCOMING] alongside the original watch-next view). */
private enum class ProgressTab {
    WATCH_NEXT,
    UPCOMING,
}

@Composable
private fun ProgressTab.label(): String = when (this) {
    ProgressTab.WATCH_NEXT -> stringResource(Res.string.tab_watch_next)
    ProgressTab.UPCOMING -> stringResource(Res.string.tab_upcoming)
}

/**
 * The Progress tab's root: a segmented switch between "Watch Next" and
 * "Upcoming" (EPIC 14) rather than a sixth bottom-nav destination — both are
 * views over the same saved-shows episode data, just sliced differently.
 */
@Composable
fun ProgressScreen(
    watchNextViewModel: ProgressViewModel,
    upcomingViewModel: UpcomingViewModel,
    onOpenDetail: (MediaId) -> Unit,
    onOpenCoWatch: (() -> Unit)?,
) {
    var selectedTab by remember { mutableStateOf(ProgressTab.WATCH_NEXT) }

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(Res.string.progress_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = MuvissSpacing.l, vertical = MuvissSpacing.s),
        )
        SegmentedSwitch(
            options = ProgressTab.entries.map { it.label() },
            selectedIndex = ProgressTab.entries.indexOf(selectedTab),
            onSelect = { selectedTab = ProgressTab.entries[it] },
            modifier = Modifier.padding(horizontal = MuvissSpacing.l),
        )
        // Co-watch (EPIC 41) is a link out, not a third tab. The two tabs above
        // answer "how far am I", over titles already in progress; a Shortlist
        // answers "what should two people start", over the titles WatchNext
        // deliberately excludes. Making it a tab here would invite folding the
        // two together, which ADR 0022 says not to do.
        // Null when this build has no sync: co-watch is built on it, so a link
        // to it would lead to a screen that can only say "sign in" (ADR 0018).
        if (onOpenCoWatch != null) {
            TextButton(onClick = onOpenCoWatch, modifier = Modifier.padding(horizontal = MuvissSpacing.l)) {
                Text(stringResource(Res.string.cowatch_entry))
            }
        }
        when (selectedTab) {
            ProgressTab.WATCH_NEXT -> WatchNextScreen(watchNextViewModel, onOpenDetail)
            ProgressTab.UPCOMING -> UpcomingScreen(upcomingViewModel, onOpenDetail)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchNextScreen(
    viewModel: ProgressViewModel,
    onOpenDetail: (MediaId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // Bumped once per tick that leaves a row to follow; the effect below does the scrolling so it
    // runs on the composition's own context rather than wherever `viewModel.tick` resumed.
    var scrollToTopRequests by remember { mutableStateOf(0) }

    // What to celebrate on screen, and when: a new token restarts the burst.
    var celebration by remember { mutableStateOf<Celebration?>(null) }
    var tokens by remember { mutableStateOf(0) }

    // One-tap tick with undo (design doc, Motion + Progress screen): tick
    // persists immediately; the snackbar's Undo reverts it via the VM. The
    // snackbar waits for the outcome (a few ms) so one message says it all,
    // and a milestone says so there too: with motion off it is all there is.
    val onTick: (WatchNextItem, onFailed: () -> Unit) -> Unit = tick@{ item, onFailed ->
        val episode = item.nextEpisode ?: return@tick
        scope.launch {
            val outcome = viewModel.tick(item) ?: return@launch
            val message = when (outcome) {
                is TickOutcome.Failed -> return@launch onFailed()
                is TickOutcome.CaughtUp -> getString(Res.string.tick_snackbar_caught_up, item.title)
                is TickOutcome.SeasonFinished -> getString(Res.string.tick_snackbar_season_done, outcome.season)
                is TickOutcome.Plain -> getString(Res.string.tick_snackbar, episode.seasonNumber, episode.episodeNumber)
            }
            // The tick made this the show most recently watched, so the list has put its row
            // first. Follow it: from further down, the row would otherwise slide off the top of
            // the screen. A show that is caught up has no row left to follow.
            if (outcome !is TickOutcome.CaughtUp) {
                scrollToTopRequests++
            }
            if (state.animateTicks && outcome !is TickOutcome.Plain) {
                celebration = Celebration(outcome, item.title, ++tokens)
            }
            val result = snackbarHostState.showSnackbar(message = message, actionLabel = getString(Res.string.action_undo))
            if (result == SnackbarResult.ActionPerformed) viewModel.untick(episode.id)
        }
    }

    LaunchedEffect(scrollToTopRequests) {
        if (scrollToTopRequests == 0) return@LaunchedEffect
        if (state.animateTicks) listState.animateScrollToItem(0) else listState.requestScrollToItem(0)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.resolveAsync())
        viewModel.consumeMessage()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.padding(top = MuvissSpacing.xxl))

                    state.error != null -> ErrorState(state.error!!, onRetry = viewModel::retry)

                    state.items.isEmpty() -> EmptyState(
                        icon = MuvissIcons.WatchNext,
                        title = stringResource(Res.string.watch_next_empty_title),
                        body = stringResource(Res.string.watch_next_empty_body),
                    )

                    else -> WatchNextList(state.items, listState, animate = state.animateTicks, onTick = onTick, onOpenDetail = onOpenDetail)
                }
                // Over the list, not in a row: a show that is caught up leaves the list,
                // and its row with it.
                celebration?.let { current ->
                    key(current.token) {
                        CelebrationBurst(label = current.label(), onFinished = { celebration = null })
                    }
                }
            }
        }
    }
}

/** A milestone being celebrated; [token] is new for each so a second one restarts the burst. */
private data class Celebration(val outcome: TickOutcome, val title: String, val token: Int)

@Composable
private fun Celebration.label(): String = when (outcome) {
    is TickOutcome.SeasonFinished -> stringResource(Res.string.tick_snackbar_season_done, outcome.season)
    else -> stringResource(Res.string.tick_snackbar_caught_up, title)
}

@Composable
private fun WatchNextList(
    items: List<WatchNextItem>,
    listState: LazyListState,
    animate: Boolean,
    onTick: (WatchNextItem, onFailed: () -> Unit) -> Unit,
    onOpenDetail: (MediaId) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(WATCH_NEXT_LIST_TAG),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
        contentPadding = PaddingValues(
            start = MuvissSpacing.m,
            end = MuvissSpacing.m,
            top = MuvissSpacing.m,
            bottom = MuvissSpacing.bottomContent,
        ),
    ) {
        items(items, key = { it.mediaId.toString() }) { item ->
            WatchNextRow(
                item,
                animate = animate,
                onTick = { onFailed -> onTick(item, onFailed) },
                onClick = { onOpenDetail(item.mediaId) },
                // A row that leaves (caught up) fades out and the rest close the gap, unless
                // motion is off, when both are instant.
                modifier = if (animate) Modifier.animateItem() else Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null),
            )
        }
    }
}

/** Tags the Watch Next list, for tests that scroll it. */
const val WATCH_NEXT_LIST_TAG = "watchNextList"

/** Tags the "S1E3 seen" sweep across a row for a moment after its tick (EPIC 52). */
const val TICK_CONFIRMATION_TAG = "tickConfirmation"

private const val SWEEP_MILLIS = 350
private const val CONFIRMATION_HOLD_MILLIS = 250L
private const val CONFIRMATION_FADE_MILLIS = 400

@Composable
private fun WatchNextRow(
    item: WatchNextItem,
    animate: Boolean,
    onTick: (onFailed: () -> Unit) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The episode this row was just ticked on, for the confirmation that sweeps over it.
    var confirmed by remember { mutableStateOf<Episode?>(null) }
    // What the row showed at the tap. The list moves on within milliseconds, so without this the
    // slide and the bar's fill would play behind the sweep: it is kept on screen under the sweep
    // and released as the sweep fades, so the change is what the confirmation dissolves into.
    var held by remember { mutableStateOf<HeldRow?>(null) }
    // The episode a tick is in flight for. A second tap on it before the row moves on would
    // write it twice (and on the last one say "caught up" twice), so the button waits. Keyed on
    // the episode shown, so it clears when the row moves on and stays clear when an undo brings
    // the episode back.
    var pending by remember(item.nextEpisode?.id) { mutableStateOf<EpisodeId?>(null) }
    val sweep = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(confirmed) {
        if (confirmed == null) return@LaunchedEffect
        sweep.snapTo(0f)
        fade.snapTo(1f)
        sweep.animateTo(1f, tween(SWEEP_MILLIS))
        delay(CONFIRMATION_HOLD_MILLIS)
        held = null
        fade.animateTo(0f, tween(CONFIRMATION_FADE_MILLIS))
        confirmed = null
    }
    val shownEpisode = held?.episode ?: item.nextEpisode
    val shownProgress = if (held != null) held?.progress else item.progress
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box {
            Row(
                Modifier.clickable(onClick = onClick).padding(9.dp),
                horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PosterImage(
                    url = item.posterUrl,
                    title = item.title,
                    modifier = Modifier.width(44.dp).height(66.dp).clip(MaterialTheme.shapes.small),
                    size = PosterSize.Thumbnail,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    NextEpisodeLine(shownEpisode, animate)
                    shownProgress?.let { progress ->
                        // Fills to its new length instead of jumping there.
                        val shown by animateFloatAsState(progress, if (animate) tween(PROGRESS_MILLIS) else snap(), label = "watchNextProgress")
                        LinearProgressIndicator(
                            progress = { shown },
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            drawStopIndicator = {},
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                        )
                    }
                }
                if (item.nextEpisode != null) {
                    TickButton(
                        enabled = pending != item.nextEpisode?.id,
                        onTick = {
                            pending = item.nextEpisode?.id
                            if (animate) {
                                held = HeldRow(item.nextEpisode, item.progress)
                                confirmed = item.nextEpisode
                            }
                            // The sweep starts at the tap; a write that then fails takes it back.
                            onTick {
                                held = null
                                confirmed = null
                                pending = null
                            }
                        },
                        animate = animate,
                        label = stringResource(Res.string.next_episode_of, item.title),
                    )
                }
            }
            confirmed?.let { episode ->
                TickConfirmation(episode, sweep, fade)
            }
        }
    }
}

private const val PROGRESS_MILLIS = 400

/** A row as it was when its tick was tapped; see `held` in [WatchNextRow]. */
private data class HeldRow(val episode: Episode?, val progress: Float?)

/** The "S1 E3 · Name" line; when the episode changes the old one leaves upward and the next comes up from below. */
@Composable
private fun NextEpisodeLine(episode: Episode?, animate: Boolean) {
    AnimatedContent(
        targetState = episode,
        transitionSpec = {
            if (animate) {
                (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) using SizeTransform(clip = true)
            } else {
                EnterTransition.None togetherWith ExitTransition.None
            }
        },
        label = "nextEpisode",
    ) { shown ->
        Text(
            text = if (shown != null) {
                stringResource(Res.string.watch_next_episode, shown.seasonNumber, shown.episodeNumber, shown.name)
            } else {
                stringResource(Res.string.watch_next_caught_up)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A wash of the primary container sweeping left to right across the row and
 * saying which episode was just marked seen, then fading to reveal the row
 * already on its next episode. Said aloud by the snackbar, so it is hidden
 * from the accessibility tree.
 */
@Composable
private fun BoxScope.TickConfirmation(episode: Episode, sweep: Animatable<Float, *>, fade: Animatable<Float, *>) {
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { alpha = fade.value }
            .drawWithContent {
                clipRect(right = size.width * sweep.value) { this@drawWithContent.drawContent() }
            }
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clearAndSetSemantics { testTag = TICK_CONFIRMATION_TAG },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            // Gone by the time the wash is half faded, so it does not sit over the episode line coming in.
            modifier = Modifier.graphicsLayer { alpha = ((fade.value - 0.5f) * 2f).coerceIn(0f, 1f) },
        ) {
            Icon(MuvissIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            Text(
                stringResource(Res.string.tick_row_confirmation, episode.seasonNumber, episode.episodeNumber),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** 44dp amber filled tick: scale pop (0.6→1.15→1.0) + haptic on tap; the row then advances reactively. The pop is skipped when [animate] is false; the haptic is not. */
@Composable
private fun TickButton(onTick: () -> Unit, animate: Boolean, label: String, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    val description = stringResource(Res.string.mark_watched_description, label)
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    IconButton(
        enabled = enabled,
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (animate) {
                scope.launch {
                    scale.snapTo(0.6f)
                    scale.animateTo(
                        targetValue = 1f,
                        animationSpec = keyframes {
                            durationMillis = 200
                            1.15f at 120
                            1f at 200
                        },
                    )
                }
            }
            onTick()
        },
        // 48dp: Android's minimum touch target (EPIC 31b, #164).
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .semantics { contentDescription = description },
    ) {
        Icon(
            MuvissIcons.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(22.dp),
        )
    }
}
