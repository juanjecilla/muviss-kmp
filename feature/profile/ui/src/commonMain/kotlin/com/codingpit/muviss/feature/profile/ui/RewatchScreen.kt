package com.codingpit.muviss.feature.profile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.text.resolve
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.profile.domain.RewatchEntry
import com.codingpit.muviss.feature.profile.domain.RewatchRanking
import com.codingpit.muviss.feature.profile.domain.RewatchWindow
import com.codingpit.muviss.feature.profile.ui.generated.resources.Res
import com.codingpit.muviss.feature.profile.ui.generated.resources.action_back
import com.codingpit.muviss.feature.profile.ui.generated.resources.most_rewatched
import com.codingpit.muviss.feature.profile.ui.generated.resources.nothing_rewatched
import com.codingpit.muviss.feature.profile.ui.generated.resources.nothing_rewatched_year
import com.codingpit.muviss.feature.profile.ui.generated.resources.ranking_movies
import com.codingpit.muviss.feature.profile.ui.generated.resources.ranking_shows
import com.codingpit.muviss.feature.profile.ui.generated.resources.rewatch_how_to
import com.codingpit.muviss.feature.profile.ui.generated.resources.rewatch_row_description
import com.codingpit.muviss.feature.profile.ui.generated.resources.rewatch_trend
import com.codingpit.muviss.feature.profile.ui.generated.resources.rewatches
import com.codingpit.muviss.feature.profile.ui.generated.resources.rewatches_episodes
import com.codingpit.muviss.feature.profile.ui.generated.resources.window_all_time
import com.codingpit.muviss.feature.profile.ui.generated.resources.window_this_year
import com.codingpit.muviss.models.MediaType
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

const val REWATCH_WINDOW_TAG = "rewatch_window"
const val REWATCH_ROW_TAG = "rewatch_row"
const val REWATCH_EMPTY_TAG = "rewatch_empty"

/**
 * The full rewatch ranking: shows and movies side by side under one window
 * control, over a trend that deliberately keeps its own.
 *
 * Shows and movies are never merged into one list. Their units differ — a
 * show counts episode rewatches, a film counts viewings — and a 200-episode
 * sitcom would otherwise outrank every film ever made simply by being long
 * (ADR 0012). Each row prints its unit for the same reason.
 */
@Composable
fun RewatchScreen(viewModel: RewatchViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RewatchScreenContent(state, onWindowSelected = viewModel::onWindowSelected, onBack = onBack, onRetry = viewModel::retry)
}

/** The screen as a function of its state — what the UI and golden tests render. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RewatchScreenContent(
    state: RewatchUiState,
    onWindowSelected: (RewatchWindow) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.most_rewatched)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MuvissIcons.Back, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = MuvissSpacing.bottomContent),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            WindowSelector(selected = state.window, onSelected = onWindowSelected)

            if (state.error != null) {
                ErrorState(state.error.resolve(), onRetry = onRetry)
            } else if (state.stats.ranking.isEmpty) {
                NothingRewatchedState(state.window, Modifier.testTag(REWATCH_EMPTY_TAG))
            } else {
                RankingLists(state.stats.ranking)
            }

            // Its own heading because its window is not the one above: a
            // rolling year is always full and comparable, where "this year"
            // renders a stub every January (ADR 0012).
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.rewatch_trend), style = MaterialTheme.typography.labelLarge)
                RewatchTrendChart(state.stats.monthly, Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowSelector(selected: RewatchWindow, onSelected: (RewatchWindow) -> Unit) {
    val options = RewatchWindow.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag(REWATCH_WINDOW_TAG)) {
        options.forEachIndexed { index, window ->
            SegmentedButton(
                selected = window == selected,
                onClick = { onSelected(window) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(stringResource(window.label))
            }
        }
    }
}

@Composable
private fun RankingLists(ranking: RewatchRanking) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (ranking.shows.isNotEmpty()) RankingList(stringResource(Res.string.ranking_shows), ranking.shows)
        if (ranking.movies.isNotEmpty()) RankingList(stringResource(Res.string.ranking_movies), ranking.movies)
    }
}

@Composable
private fun RankingList(heading: String, entries: List<RewatchEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(heading, style = MaterialTheme.typography.labelLarge)
        entries.forEachIndexed { index, entry -> RewatchRow(position = index + 1, entry = entry) }
    }
}

@Composable
private fun RewatchRow(position: Int, entry: RewatchEntry, modifier: Modifier = Modifier) {
    val label = entry.rewatchLabel()
    val description = stringResource(Res.string.rewatch_row_description, position, entry.title, label)
    Row(
        modifier
            .fillMaxWidth()
            .testTag(REWATCH_ROW_TAG)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$position",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PosterImage(
            url = entry.posterUrl,
            title = entry.title,
            modifier = Modifier.size(width = 40.dp, height = 60.dp).clip(RoundedCornerShape(6.dp)),
            size = PosterSize.Thumbnail,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NothingRewatchedState(window: RewatchWindow, modifier: Modifier = Modifier) {
    EmptyState(
        modifier = modifier,
        icon = MuvissIcons.WatchNext,
        title = stringResource(if (window == RewatchWindow.THIS_YEAR) Res.string.nothing_rewatched_year else Res.string.nothing_rewatched),
        body = stringResource(Res.string.rewatch_how_to),
    )
}

/** "41 episode rewatches" for a show, "5 rewatches" for a film — the unit is never left implicit (ADR 0012). */
@Composable
internal fun RewatchEntry.rewatchLabel(): String = pluralStringResource(if (mediaType == MediaType.TV) Res.plurals.rewatches_episodes else Res.plurals.rewatches, rewatches, rewatches)

internal val RewatchWindow.label: StringResource
    get() = when (this) {
        RewatchWindow.ALL_TIME -> Res.string.window_all_time
        RewatchWindow.THIS_YEAR -> Res.string.window_this_year
    }
