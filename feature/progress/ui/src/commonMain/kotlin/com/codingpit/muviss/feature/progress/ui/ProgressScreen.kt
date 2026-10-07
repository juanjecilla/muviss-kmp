package com.codingpit.muviss.feature.progress.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.component.SegmentedSwitch
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.text.resolve
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
import com.codingpit.muviss.feature.progress.ui.generated.resources.tick_snackbar
import com.codingpit.muviss.feature.progress.ui.generated.resources.watch_next_caught_up
import com.codingpit.muviss.feature.progress.ui.generated.resources.watch_next_empty_body
import com.codingpit.muviss.feature.progress.ui.generated.resources.watch_next_empty_title
import com.codingpit.muviss.feature.progress.ui.generated.resources.watch_next_episode
import com.codingpit.muviss.models.MediaId
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

    // One-tap tick with undo (design doc, Motion + Progress screen): tick
    // persists immediately; the snackbar's Undo reverts it via the VM.
    val onTick: (WatchNextItem) -> Unit = tick@{ item ->
        val episode = item.nextEpisode ?: return@tick
        viewModel.tickNext(item)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = getString(Res.string.tick_snackbar, episode.seasonNumber, episode.episodeNumber),
                actionLabel = getString(Res.string.action_undo),
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.untick(episode.id)
        }
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

                    state.error != null -> ErrorState(state.error!!.resolve(), onRetry = viewModel::retry)

                    state.items.isEmpty() -> EmptyState(
                        icon = MuvissIcons.WatchNext,
                        title = stringResource(Res.string.watch_next_empty_title),
                        body = stringResource(Res.string.watch_next_empty_body),
                    )

                    else -> WatchNextList(state.items, onTick = onTick, onOpenDetail = onOpenDetail)
                }
            }
        }
    }
}

@Composable
private fun WatchNextList(
    items: List<WatchNextItem>,
    onTick: (WatchNextItem) -> Unit,
    onOpenDetail: (MediaId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
        contentPadding = PaddingValues(
            start = MuvissSpacing.m,
            end = MuvissSpacing.m,
            top = MuvissSpacing.m,
            bottom = MuvissSpacing.bottomContent,
        ),
    ) {
        items(items, key = { it.mediaId.toString() }) { item ->
            WatchNextRow(item, onTick = { onTick(item) }, onClick = { onOpenDetail(item.mediaId) })
        }
    }
}

@Composable
private fun WatchNextRow(
    item: WatchNextItem,
    onTick: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
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
                val episode = item.nextEpisode
                Text(
                    text = if (episode != null) {
                        stringResource(Res.string.watch_next_episode, episode.seasonNumber, episode.episodeNumber, episode.name)
                    } else {
                        stringResource(Res.string.watch_next_caught_up)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.progress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        drawStopIndicator = {},
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    )
                }
            }
            if (item.nextEpisode != null) {
                TickButton(onTick, label = stringResource(Res.string.next_episode_of, item.title))
            }
        }
    }
}

/** 44dp amber filled tick: scale pop (0.6→1.15→1.0) + haptic on tap; the row then advances reactively. */
@Composable
private fun TickButton(onTick: () -> Unit, label: String) {
    val haptics = LocalHapticFeedback.current
    val description = stringResource(Res.string.mark_watched_description, label)
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    IconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
            onTick()
        },
        modifier = Modifier
            .size(44.dp)
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
