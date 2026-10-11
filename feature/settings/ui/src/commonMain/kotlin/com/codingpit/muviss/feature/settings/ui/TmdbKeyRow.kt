package com.codingpit.muviss.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.settings.domain.TmdbDataSource
import com.codingpit.muviss.feature.settings.ui.generated.resources.Res
import com.codingpit.muviss.feature.settings.ui.generated.resources.action_cancel
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_key_checking
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_key_field
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_key_problem_could_not_check
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_key_problem_malformed
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_key_problem_rejected
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_key_save
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_key_use_muviss
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_source
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_source_body
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_source_muviss
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_source_yours
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Test tags: the row, and the dialog's field and buttons. */
const val TMDB_KEY_ROW_TAG = "tmdbKeyRow"
const val TMDB_KEY_FIELD_TAG = "tmdbKeyField"
const val TMDB_KEY_SAVE_TAG = "tmdbKeySave"
const val TMDB_KEY_USE_MUVISS_TAG = "tmdbKeyUseMuviss"

/**
 * "TMDB data source — Muviss key ›" (EPIC 46). Tapping opens a dialog to paste, check and save
 * an Own TMDB Key, or to go back to Muviss's.
 *
 * The field is masked and its text lives only here: it goes to [onSave] and is dropped, so
 * the key is never in a view model's state. A problem stays under the field until the
 * person edits it or closes the dialog.
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
        var text by remember { mutableStateOf("") }
        val close = {
            open = false
            onDismiss()
        }
        AlertDialog(
            onDismissRequest = { if (!state.checking) close() },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                    Text(stringResource(Res.string.tmdb_source_body), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(Res.string.tmdb_key_field)) },
                        singleLine = true,
                        enabled = !state.checking,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = state.problem != null,
                        modifier = Modifier.fillMaxWidth().testTag(TMDB_KEY_FIELD_TAG),
                    )
                    state.problem?.let { problem ->
                        Text(stringResource(problem.message()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (state.checking) {
                        Text(stringResource(Res.string.tmdb_key_checking), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(text) {
                            text = ""
                            open = false
                        }
                    },
                    enabled = text.isNotBlank() && !state.checking,
                    modifier = Modifier.testTag(TMDB_KEY_SAVE_TAG),
                ) { Text(stringResource(Res.string.tmdb_key_save)) }
            },
            dismissButton = {
                Row {
                    if (state.source == TmdbDataSource.YOURS) {
                        TextButton(
                            onClick = {
                                onUseMuviss {
                                    text = ""
                                    open = false
                                }
                            },
                            enabled = !state.checking,
                            modifier = Modifier.testTag(TMDB_KEY_USE_MUVISS_TAG),
                        ) { Text(stringResource(Res.string.tmdb_key_use_muviss)) }
                    }
                    TextButton(onClick = close, enabled = !state.checking) { Text(stringResource(Res.string.action_cancel)) }
                }
            },
        )
    }
}

private fun TmdbDataSource.label(): StringResource = when (this) {
    TmdbDataSource.MUVISS -> Res.string.tmdb_source_muviss
    TmdbDataSource.YOURS -> Res.string.tmdb_source_yours
}

private fun TmdbKeyProblem.message(): StringResource = when (this) {
    TmdbKeyProblem.MALFORMED -> Res.string.tmdb_key_problem_malformed
    TmdbKeyProblem.REJECTED -> Res.string.tmdb_key_problem_rejected
    TmdbKeyProblem.COULD_NOT_CHECK -> Res.string.tmdb_key_problem_could_not_check
}
