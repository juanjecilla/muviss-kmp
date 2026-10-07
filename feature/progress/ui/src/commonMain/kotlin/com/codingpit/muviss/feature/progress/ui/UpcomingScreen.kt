package com.codingpit.muviss.feature.progress.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.text.resolve
import com.codingpit.muviss.core.designsystem.text.resolveAsync
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.progress.domain.UpcomingBucket
import com.codingpit.muviss.feature.progress.domain.UpcomingDate
import com.codingpit.muviss.feature.progress.ui.generated.resources.Res
import com.codingpit.muviss.feature.progress.ui.generated.resources.bucket_later
import com.codingpit.muviss.feature.progress.ui.generated.resources.bucket_this_week
import com.codingpit.muviss.feature.progress.ui.generated.resources.bucket_today
import com.codingpit.muviss.feature.progress.ui.generated.resources.date_month_day
import com.codingpit.muviss.feature.progress.ui.generated.resources.date_today
import com.codingpit.muviss.feature.progress.ui.generated.resources.date_tomorrow
import com.codingpit.muviss.feature.progress.ui.generated.resources.month_1
import com.codingpit.muviss.feature.progress.ui.generated.resources.month_10
import com.codingpit.muviss.feature.progress.ui.generated.resources.month_11
import com.codingpit.muviss.feature.progress.ui.generated.resources.month_12
import com.codingpit.muviss.feature.progress.ui.generated.resources.month_2
import com.codingpit.muviss.feature.progress.ui.generated.resources.month_3
import com.codingpit.muviss.feature.progress.ui.generated.resources.month_4
import com.codingpit.muviss.feature.progress.ui.generated.resources.month_5
import com.codingpit.muviss.feature.progress.ui.generated.resources.month_6
import com.codingpit.muviss.feature.progress.ui.generated.resources.month_7
import com.codingpit.muviss.feature.progress.ui.generated.resources.month_8
import com.codingpit.muviss.feature.progress.ui.generated.resources.month_9
import com.codingpit.muviss.feature.progress.ui.generated.resources.upcoming_empty_body
import com.codingpit.muviss.feature.progress.ui.generated.resources.upcoming_empty_title
import com.codingpit.muviss.feature.progress.ui.generated.resources.upcoming_episode
import com.codingpit.muviss.feature.progress.ui.generated.resources.upcoming_none_body
import com.codingpit.muviss.feature.progress.ui.generated.resources.upcoming_none_title
import com.codingpit.muviss.feature.progress.ui.generated.resources.weekday_0
import com.codingpit.muviss.feature.progress.ui.generated.resources.weekday_1
import com.codingpit.muviss.feature.progress.ui.generated.resources.weekday_2
import com.codingpit.muviss.feature.progress.ui.generated.resources.weekday_3
import com.codingpit.muviss.feature.progress.ui.generated.resources.weekday_4
import com.codingpit.muviss.feature.progress.ui.generated.resources.weekday_5
import com.codingpit.muviss.feature.progress.ui.generated.resources.weekday_6
import com.codingpit.muviss.models.MediaId
import org.jetbrains.compose.resources.stringResource

/**
 * The Progress tab's Upcoming segment (EPIC 14): an agenda of future-dated
 * episodes across the library's TV shows, grouped into Today / This week /
 * Later. Mirrors [WatchNextScreen]'s loading/error/empty/list shape.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingScreen(
    viewModel: UpcomingViewModel,
    onOpenDetail: (MediaId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.resolveAsync())
        viewModel.consumeMessage()
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.padding(top = MuvissSpacing.xxl))
                    state.error != null -> ErrorState(state.error!!.resolve(), onRetry = viewModel::retry)
                    state.groups.isEmpty() -> UpcomingEmptyState(hasLibraryEntries = state.hasLibraryEntries)
                    else -> UpcomingList(state.groups, onOpenDetail)
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun UpcomingEmptyState(hasLibraryEntries: Boolean) {
    if (hasLibraryEntries) {
        EmptyState(
            icon = MuvissIcons.Calendar,
            title = stringResource(Res.string.upcoming_none_title),
            body = stringResource(Res.string.upcoming_none_body),
        )
    } else {
        EmptyState(
            icon = MuvissIcons.Calendar,
            title = stringResource(Res.string.upcoming_empty_title),
            body = stringResource(Res.string.upcoming_empty_body),
        )
    }
}

@Composable
private fun UpcomingList(
    groups: List<UpcomingUiGroup>,
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
        groups.forEach { group ->
            item(key = "header-${group.bucket}", contentType = "header") {
                Text(
                    group.bucket.label().uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MuvissSpacing.s, bottom = MuvissSpacing.xs),
                )
            }
            items(group.rows, key = { it.episode.id.toString() }, contentType = { "episode" }) { row ->
                UpcomingRow(row, onClick = { onOpenDetail(row.mediaId) })
            }
        }
    }
}

/** Agenda row: amber date block left (mock's "18 JUL" treatment), episode info right. */
@Composable
private fun UpcomingRow(
    row: UpcomingRow,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(MuvissSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.date.label(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        PosterImage(
            url = row.posterUrl,
            title = row.title,
            modifier = Modifier.width(40.dp).height(60.dp).clip(MaterialTheme.shapes.extraSmall),
            size = PosterSize.Thumbnail,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(Res.string.upcoming_episode, row.episode.seasonNumber, row.episode.episodeNumber, row.episode.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UpcomingBucket.label(): String = when (this) {
    UpcomingBucket.TODAY -> stringResource(Res.string.bucket_today)
    UpcomingBucket.THIS_WEEK -> stringResource(Res.string.bucket_this_week)
    UpcomingBucket.LATER -> stringResource(Res.string.bucket_later)
}

private val WEEKDAYS = listOf(Res.string.weekday_0, Res.string.weekday_1, Res.string.weekday_2, Res.string.weekday_3, Res.string.weekday_4, Res.string.weekday_5, Res.string.weekday_6)
private val MONTHS = listOf(
    Res.string.month_1, Res.string.month_2, Res.string.month_3, Res.string.month_4, Res.string.month_5, Res.string.month_6,
    Res.string.month_7, Res.string.month_8, Res.string.month_9, Res.string.month_10, Res.string.month_11, Res.string.month_12,
)

/** The agenda's date column in the user's language: the domain decides which case, the words are ours. */
@Composable
private fun UpcomingDate.label(): String = when (this) {
    UpcomingDate.Today -> stringResource(Res.string.date_today)
    UpcomingDate.Tomorrow -> stringResource(Res.string.date_tomorrow)
    is UpcomingDate.Weekday -> stringResource(WEEKDAYS[index])
    is UpcomingDate.Date -> stringResource(Res.string.date_month_day, stringResource(MONTHS[month - 1]), day)
}
