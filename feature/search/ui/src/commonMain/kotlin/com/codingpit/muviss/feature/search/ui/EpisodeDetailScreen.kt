package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.common.epochDayOf
import com.codingpit.muviss.core.designsystem.component.CarouselHeader
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.text.dateText
import com.codingpit.muviss.core.designsystem.text.resolve
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.action_cancel
import com.codingpit.muviss.feature.search.ui.generated.resources.clear
import com.codingpit.muviss.feature.search.ui.generated.resources.clear_history
import com.codingpit.muviss.feature.search.ui.generated.resources.clear_history_body
import com.codingpit.muviss.feature.search.ui.generated.resources.clear_history_question
import com.codingpit.muviss.feature.search.ui.generated.resources.crew
import com.codingpit.muviss.feature.search.ui.generated.resources.episode_code
import com.codingpit.muviss.feature.search.ui.generated.resources.episode_mark_watched
import com.codingpit.muviss.feature.search.ui.generated.resources.episode_not_watched
import com.codingpit.muviss.feature.search.ui.generated.resources.episode_watched_again
import com.codingpit.muviss.feature.search.ui.generated.resources.episode_watched_many
import com.codingpit.muviss.feature.search.ui.generated.resources.episode_watched_once
import com.codingpit.muviss.feature.search.ui.generated.resources.guest_stars
import com.codingpit.muviss.feature.search.ui.generated.resources.runtime_minutes
import com.codingpit.muviss.feature.search.ui.generated.resources.tmdb_score
import com.codingpit.muviss.feature.search.ui.generated.resources.undo_last
import com.codingpit.muviss.models.EpisodeCredit
import com.codingpit.muviss.models.EpisodeDetails
import org.jetbrains.compose.resources.stringResource

/**
 * One episode, in full: the source's still, overview, guest cast and crew,
 * plus the user's own watch history of it.
 *
 * The history section is the reason this screen exists as more than a nicety —
 * it is where "clear watch history" lives. The detail screen's "I ticked it by
 * mistake" deliberately only drops the newest viewing, so wiping an episode's
 * history outright needs somewhere it cannot be reached by a stray tap.
 */
@Composable
fun EpisodeDetailScreen(
    viewModel: EpisodeDetailViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).padding(MuvissSpacing.xl))

            state.error != null -> ErrorState(
                message = state.error!!.resolve(),
                onRetry = viewModel::load,
                modifier = Modifier.align(Alignment.Center),
            )

            state.details != null -> EpisodeBody(
                details = state.details!!,
                state = state,
                onWatchedAgain = viewModel::recordRewatch,
                onUndoLatest = viewModel::undoLatestPlay,
                onClearHistory = { confirmClear = true },
            )
        }

        BackButton(onBack, Modifier.statusBarsPadding().padding(12.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(Res.string.clear_history_question)) },
            text = { Text(stringResource(Res.string.clear_history_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    confirmClear = false
                }) { Text(stringResource(Res.string.clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
private fun EpisodeBody(
    details: EpisodeDetails,
    state: EpisodeDetailUiState,
    onWatchedAgain: () -> Unit,
    onUndoLatest: () -> Unit,
    onClearHistory: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = MuvissSpacing.bottomContent),
    ) {
        EpisodeStill(details)
        Column(
            Modifier.padding(horizontal = MuvissSpacing.l).padding(top = MuvissSpacing.m),
            verticalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        ) {
            Text(
                stringResource(Res.string.episode_code, details.seasonNumber, details.episodeNumber),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(details.name, style = MaterialTheme.typography.headlineSmall)
            EpisodeMetadataLine(details)

            details.overview?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            WatchHistorySection(state, onWatchedAgain, onUndoLatest, onClearHistory)

            CreditRow(stringResource(Res.string.guest_stars), details.guestStars) { it.character }
            CreditRow(stringResource(Res.string.crew), details.crew) { it.job }
        }
    }
}

@Composable
private fun EpisodeStill(details: EpisodeDetails) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        PosterImage(url = details.stillUrl, title = details.name, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun EpisodeMetadataLine(details: EpisodeDetails) {
    val parts = buildList {
        details.runtimeMinutes?.let { add(stringResource(Res.string.runtime_minutes, it)) }
        details.airDateEpochDay?.let { add(dateText(it)) }
        // Labelled "TMDB" for the same reason the title screen's is: the stars
        // elsewhere in the app are the user's own rating, out of five.
        details.voteAverage?.let { add(stringResource(Res.string.tmdb_score, it.toString().take(3))) }
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The user's own half of the screen: how many times, and when. */
@Composable
private fun WatchHistorySection(
    state: EpisodeDetailUiState,
    onWatchedAgain: () -> Unit,
    onUndoLatest: () -> Unit,
    onClearHistory: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(MuvissSpacing.m), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
            Text(
                when (state.playCount) {
                    0 -> stringResource(Res.string.episode_not_watched)
                    1 -> stringResource(Res.string.episode_watched_once)
                    else -> stringResource(Res.string.episode_watched_many, state.playCount)
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (state.plays.isNotEmpty()) {
                Text(
                    state.plays.map { dateText(epochDayOf(it.watchedAtEpochMs)) }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onWatchedAgain, shape = CircleShape) {
                    Text(stringResource(if (state.seen) Res.string.episode_watched_again else Res.string.episode_mark_watched))
                }
                if (state.seen) {
                    OutlinedButton(onClick = onUndoLatest, shape = CircleShape) { Text(stringResource(Res.string.undo_last)) }
                }
            }
            if (state.seen) {
                TextButton(onClick = onClearHistory, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(Res.string.clear_history), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun CreditRow(title: String, credits: List<EpisodeCredit>, subtitleOf: (EpisodeCredit) -> String?) {
    if (credits.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
        CarouselHeader(title, modifier = Modifier.padding(top = MuvissSpacing.s))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m)) {
            items(credits, key = { it.name + it.character + it.job }) { credit ->
                Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        if (credit.profileUrl != null) {
                            PosterImage(
                                url = credit.profileUrl,
                                title = credit.name,
                                modifier = Modifier.size(64.dp).clip(CircleShape),
                                size = PosterSize.Thumbnail,
                            )
                        } else {
                            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                                Icon(MuvissIcons.Profile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(
                        credit.name,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = MuvissSpacing.xs),
                    )
                    subtitleOf(credit)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
