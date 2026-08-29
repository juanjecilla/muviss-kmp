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
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.profile.domain.RewatchEntry
import com.codingpit.muviss.feature.profile.domain.RewatchRanking
import com.codingpit.muviss.feature.profile.domain.RewatchWindow
import com.codingpit.muviss.models.MediaType

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
    RewatchScreenContent(state, onWindowSelected = viewModel::onWindowSelected, onBack = onBack)
}

/** The screen as a function of its state — what the UI and golden tests render. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RewatchScreenContent(
    state: RewatchUiState,
    onWindowSelected: (RewatchWindow) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Most rewatched") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MuvissIcons.Back, contentDescription = "Back")
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
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            WindowSelector(selected = state.window, onSelected = onWindowSelected)

            if (state.stats.ranking.isEmpty) {
                NothingRewatchedState(state.window, Modifier.testTag(REWATCH_EMPTY_TAG))
            } else {
                RankingLists(state.stats.ranking)
            }

            // Its own heading because its window is not the one above: a
            // rolling year is always full and comparable, where "this year"
            // renders a stub every January (ADR 0012).
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Rewatches · last 12 months", style = MaterialTheme.typography.labelLarge)
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
                Text(window.label)
            }
        }
    }
}

@Composable
private fun RankingLists(ranking: RewatchRanking) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (ranking.shows.isNotEmpty()) RankingList("Shows", ranking.shows)
        if (ranking.movies.isNotEmpty()) RankingList("Movies", ranking.movies)
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
    Row(
        modifier
            .fillMaxWidth()
            .testTag(REWATCH_ROW_TAG)
            .semantics { contentDescription = "$position. ${entry.title}, ${entry.rewatchLabel()}" },
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
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                entry.rewatchLabel(),
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
        title = if (window == RewatchWindow.THIS_YEAR) "Nothing rewatched this year" else "Nothing rewatched yet",
        body = REWATCH_HOW_TO,
    )
}

/**
 * Names the gesture, because nothing else in the app does. "Watched again"
 * shipped with the rewatch history itself (ADR 0011) and every existing
 * install upgrades with zero rewatches — its backfill gave each already-seen
 * episode exactly one viewing — so this empty state is day one for everyone.
 */
internal const val REWATCH_HOW_TO = "Tap an episode you've already seen and choose \"Watched again\"."

/** "41 episode rewatches" for a show, "5 rewatches" for a film — the unit is never left implicit (ADR 0012). */
internal fun RewatchEntry.rewatchLabel(): String {
    val noun = if (rewatches == 1) "rewatch" else "rewatches"
    return if (mediaType == MediaType.TV) "$rewatches episode $noun" else "$rewatches $noun"
}

internal val RewatchWindow.label: String
    get() = when (this) {
        RewatchWindow.ALL_TIME -> "All time"
        RewatchWindow.THIS_YEAR -> "This year"
    }
