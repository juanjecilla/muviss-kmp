package com.codingpit.muviss.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.component.TmdbKeyDialog
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.settings.api.TmdbDataSource
import com.codingpit.muviss.feature.settings.ui.generated.resources.Res
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_source
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_source_muviss
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_source_yours
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Test tag of the row; the dialog's own tags are `TMDB_KEY_*_TAG` in `:core:designsystem`. */
const val TMDB_KEY_ROW_TAG = "tmdbKeyRow"

/**
 * "TMDB data source — Muviss key ›" (EPIC 46). Tapping opens the shared [TmdbKeyDialog] to paste,
 * check and save an Own TMDB Key, or to go back to Muviss's.
 */
@Composable
internal fun TmdbKeyRow(
    state: TmdbKeyUiState,
    onSave: (raw: String, onSaved: () -> Unit) -> Unit,
    onUseMuviss: (onDone: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.tmdb_source)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { open = true }
            .padding(vertical = MuvissSpacing.m)
            .testTag(TMDB_KEY_ROW_TAG),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
            Text(
                stringResource(state.source.label()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                MuvissIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    if (open) {
        TmdbKeyDialog(
            title = label,
            usingOwnKey = state.source == TmdbDataSource.YOURS,
            checking = state.checking,
            problem = state.problem,
            onSave = { raw -> onSave(raw) { open = false } },
            onUseMuviss = { onUseMuviss { open = false } },
            onDismiss = {
                open = false
                onDismiss()
            },
        )
    }
}

private fun TmdbDataSource.label(): StringResource = when (this) {
    TmdbDataSource.MUVISS -> Res.string.tmdb_source_muviss
    TmdbDataSource.YOURS -> Res.string.tmdb_source_yours
}
