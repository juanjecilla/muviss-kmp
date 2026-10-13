package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.codingpit.muviss.core.designsystem.generated.resources.Res
import com.codingpit.muviss.core.designsystem.generated.resources.action_cancel
import com.codingpit.muviss.core.designsystem.generated.resources.tmdb_key_checking
import com.codingpit.muviss.core.designsystem.generated.resources.tmdb_key_field
import com.codingpit.muviss.core.designsystem.generated.resources.tmdb_key_problem_could_not_check
import com.codingpit.muviss.core.designsystem.generated.resources.tmdb_key_problem_malformed
import com.codingpit.muviss.core.designsystem.generated.resources.tmdb_key_problem_rejected
import com.codingpit.muviss.core.designsystem.generated.resources.tmdb_key_save
import com.codingpit.muviss.core.designsystem.generated.resources.tmdb_key_use_muviss
import com.codingpit.muviss.core.designsystem.generated.resources.tmdb_source_body
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Why the last attempt to save a key did not take. Shown under the field; never carries TMDB's or Ktor's text. */
enum class TmdbKeyProblem { MALFORMED, REJECTED, COULD_NOT_CHECK }

const val TMDB_KEY_FIELD_TAG = "tmdbKeyField"
const val TMDB_KEY_SAVE_TAG = "tmdbKeySave"
const val TMDB_KEY_USE_MUVISS_TAG = "tmdbKeyUseMuviss"

/**
 * Paste, check and save an Own TMDB Key — or go back to Muviss's (EPIC 46). One dialog for
 * the two places that offer it, Settings and Onboarding, so they cannot drift apart.
 *
 * Stateless about the key itself: the field's text lives only here and goes to [onSave], so a
 * key is never in a view model's state. The field is masked. The caller closes the dialog
 * once the key is saved; it stays up, with [problem] under the field, when it is not.
 */
@Composable
fun TmdbKeyDialog(
    title: String,
    usingOwnKey: Boolean,
    checking: Boolean,
    problem: TmdbKeyProblem?,
    onSave: (raw: String) -> Unit,
    onUseMuviss: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                Text(stringResource(Res.string.tmdb_source_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(Res.string.tmdb_key_field)) },
                    singleLine = true,
                    enabled = !checking,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = problem != null,
                    modifier = Modifier.fillMaxWidth().testTag(TMDB_KEY_FIELD_TAG),
                )
                problem?.let {
                    Text(stringResource(it.message()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (checking) {
                    Text(stringResource(Res.string.tmdb_key_checking), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank() && !checking,
                modifier = Modifier.testTag(TMDB_KEY_SAVE_TAG),
            ) { Text(stringResource(Res.string.tmdb_key_save)) }
        },
        dismissButton = {
            Row {
                if (usingOwnKey) {
                    TextButton(
                        onClick = onUseMuviss,
                        enabled = !checking,
                        modifier = Modifier.testTag(TMDB_KEY_USE_MUVISS_TAG),
                    ) { Text(stringResource(Res.string.tmdb_key_use_muviss)) }
                }
                TextButton(onClick = onDismiss, enabled = !checking) { Text(stringResource(Res.string.action_cancel)) }
            }
        },
    )
}

private fun TmdbKeyProblem.message(): StringResource = when (this) {
    TmdbKeyProblem.MALFORMED -> Res.string.tmdb_key_problem_malformed
    TmdbKeyProblem.REJECTED -> Res.string.tmdb_key_problem_rejected
    TmdbKeyProblem.COULD_NOT_CHECK -> Res.string.tmdb_key_problem_could_not_check
}
