package com.codingpit.muviss.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.settings.ui.generated.resources.Res
import com.codingpit.muviss.feature.settings.ui.generated.resources.muted_genres_available
import com.codingpit.muviss.feature.settings.ui.generated.resources.muted_genres_body
import com.codingpit.muviss.feature.settings.ui.generated.resources.muted_genres_in_search
import com.codingpit.muviss.feature.settings.ui.generated.resources.muted_genres_in_search_body
import com.codingpit.muviss.feature.settings.ui.generated.resources.muted_genres_movies
import com.codingpit.muviss.feature.settings.ui.generated.resources.muted_genres_none
import com.codingpit.muviss.feature.settings.ui.generated.resources.muted_genres_offline
import com.codingpit.muviss.feature.settings.ui.generated.resources.muted_genres_retry
import com.codingpit.muviss.feature.settings.ui.generated.resources.muted_genres_title
import com.codingpit.muviss.feature.settings.ui.generated.resources.muted_genres_tv
import com.codingpit.muviss.feature.settings.ui.generated.resources.muted_genres_your
import com.codingpit.muviss.models.MediaType
import org.jetbrains.compose.resources.stringResource

const val MUTED_GENRES_LIST_TAG = "mutedGenresList"

/** Test tag of a row in the "Muted" section, suffixed with `<type>-<id>`: `mutedGenre-tv-10767`. */
fun mutedGenreTag(option: GenreOption): String = "mutedGenre-${option.key.type.wireName}-${option.key.id}"

/** Test tag of a row in the "All genres" section: the same genre can be in both, so the tags differ. */
fun availableGenreTag(option: GenreOption): String = "availableGenre-${option.key.type.wireName}-${option.key.id}"

@Composable
fun MutedGenresScreen(viewModel: MutedGenresViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MutedGenresContent(
        state = state,
        onToggle = viewModel::onToggle,
        onBrowse = viewModel::browse,
        onRetry = viewModel::retry,
        onInSearchToggled = viewModel::onInSearchToggled,
    )
}

/** Stateless so the screenshots and tests render exactly what ships. */
@Composable
fun MutedGenresContent(
    state: MutedGenresUiState,
    onToggle: (GenreOption) -> Unit,
    onBrowse: (MediaType) -> Unit,
    onRetry: () -> Unit,
    onInSearchToggled: (Boolean) -> Unit,
) {
    if (state.loading) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.padding(top = MuvissSpacing.xxl))
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = MuvissSpacing.l).testTag(MUTED_GENRES_LIST_TAG),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs),
    ) {
        item {
            Text(
                stringResource(Res.string.muted_genres_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = MuvissSpacing.l, bottom = MuvissSpacing.xs),
            )
            Text(stringResource(Res.string.muted_genres_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SwitchRow(
                label = stringResource(Res.string.muted_genres_in_search),
                description = stringResource(Res.string.muted_genres_in_search_body),
                checked = state.inSearch,
                onToggle = onInSearchToggled,
            )
        }
        item { Header(stringResource(Res.string.muted_genres_your)) }
        if (state.muted.isEmpty()) {
            item { Text(stringResource(Res.string.muted_genres_none), style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.muted, key = { mutedGenreTag(it) }) { GenreRow(it, mutedGenreTag(it), onToggle) }
        item {
            Header(stringResource(Res.string.muted_genres_available))
            Row(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                FilterChip(
                    selected = state.browsing == MediaType.TV,
                    onClick = { onBrowse(MediaType.TV) },
                    label = { Text(stringResource(Res.string.muted_genres_tv)) },
                )
                FilterChip(
                    selected = state.browsing == MediaType.MOVIE,
                    onClick = { onBrowse(MediaType.MOVIE) },
                    label = { Text(stringResource(Res.string.muted_genres_movies)) },
                )
            }
        }
        when {
            state.loadingAvailable -> item { CircularProgressIndicator(Modifier.padding(MuvissSpacing.m)) }

            state.availableFailed -> item {
                Column {
                    Text(stringResource(Res.string.muted_genres_offline), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetry) { Text(stringResource(Res.string.muted_genres_retry)) }
                }
            }

            else -> items(state.available, key = { availableGenreTag(it) }) { GenreRow(it, availableGenreTag(it), onToggle) }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = MuvissSpacing.m))
}

@Composable
private fun GenreRow(option: GenreOption, tag: String, onToggle: (GenreOption) -> Unit) {
    // The whole row is the control: a bare Checkbox beside its label would be announced with no name.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = option.muted, role = Role.Checkbox, onValueChange = { onToggle(option) })
            .padding(vertical = MuvissSpacing.xs)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = option.muted, onCheckedChange = null)
        Text(option.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = MuvissSpacing.s))
    }
}
