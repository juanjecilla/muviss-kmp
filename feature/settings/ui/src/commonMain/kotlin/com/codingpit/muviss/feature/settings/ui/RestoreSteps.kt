package com.codingpit.muviss.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.text.dateText
import com.codingpit.muviss.feature.settings.domain.RestoreResult
import com.codingpit.muviss.feature.settings.ui.generated.resources.Res
import com.codingpit.muviss.feature.settings.ui.generated.resources.action_cancel
import com.codingpit.muviss.feature.settings.ui.generated.resources.action_done
import com.codingpit.muviss.feature.settings.ui.generated.resources.backup_episodes
import com.codingpit.muviss.feature.settings.ui.generated.resources.backup_explainer
import com.codingpit.muviss.feature.settings.ui.generated.resources.backup_from
import com.codingpit.muviss.feature.settings.ui.generated.resources.backup_lists
import com.codingpit.muviss.feature.settings.ui.generated.resources.backup_titles
import com.codingpit.muviss.feature.settings.ui.generated.resources.import_another
import com.codingpit.muviss.feature.settings.ui.generated.resources.kept_rows
import com.codingpit.muviss.feature.settings.ui.generated.resources.restore_action
import com.codingpit.muviss.feature.settings.ui.generated.resources.restore_complete
import com.codingpit.muviss.feature.settings.ui.generated.resources.restored_rows
import com.codingpit.muviss.feature.settings.ui.generated.resources.restoring
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Tag on the Restore button, the one step between a picked backup and a merged library. */
internal const val RESTORE_CONFIRM_TAG = "import-restore-confirm"

/**
 * What a picked Muviss backup holds, and the button that restores it (EPIC 29,
 * #72). Says up front that newer edits on this device survive, because
 * "restore" otherwise reads as "replace".
 */
@Composable
internal fun BackupPreviewStep(step: ImportStep.BackupPreview, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val summary = step.summary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.backup_from, dateText(summary.exportedAtEpochMs.floorDiv(MILLIS_PER_DAY))),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(pluralStringResource(Res.plurals.backup_titles, summary.titleCount, summary.titleCount), style = MaterialTheme.typography.bodyMedium)
        Text(pluralStringResource(Res.plurals.backup_episodes, summary.episodeCount, summary.episodeCount), style = MaterialTheme.typography.bodyMedium)
        Text(pluralStringResource(Res.plurals.backup_lists, summary.listCount, summary.listCount), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(Res.string.backup_explainer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().testTag(RESTORE_CONFIRM_TAG)) { Text(stringResource(Res.string.restore_action)) }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.action_cancel)) }
    }
}

@Composable
internal fun RestoringStep() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.restoring), style = MaterialTheme.typography.titleMedium)
        CircularProgressIndicator()
    }
}

@Composable
internal fun RestoredStep(result: RestoreResult, onImportAnother: () -> Unit, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(MuvissIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(40.dp))
        Text(stringResource(Res.string.restore_complete), style = MaterialTheme.typography.titleMedium)
        Text(pluralStringResource(Res.plurals.restored_rows, result.restored, result.restored), style = MaterialTheme.typography.bodyMedium)
        if (result.kept > 0) {
            Text(pluralStringResource(Res.plurals.kept_rows, result.kept, result.kept), style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.action_done)) }
        OutlinedButton(onClick = onImportAnother, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.import_another)) }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
