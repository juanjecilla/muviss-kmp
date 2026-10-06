package com.codingpit.muviss.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.StatTile
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.feature.settings.domain.ImportApplyResult
import com.codingpit.muviss.feature.settings.domain.ImportSource
import com.codingpit.muviss.feature.settings.domain.UnresolvedImportTitle
import kotlinx.coroutines.launch

/**
 * The Settings > Import screen (EPIC 18): pick a file, preview what it
 * contains, confirm, watch it apply, then a result summary. Renders one of
 * five layouts per [ImportStep] — see [ImportViewModel] for the state
 * machine and why file-picking happens here rather than in the view model.
 */
@Composable
fun ImportScreen(viewModel: ImportViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val fileImporter = rememberFileImporter()
    val scope = rememberCoroutineScope()

    fun pickFile() {
        scope.launch {
            val picked = fileImporter.pickFile() ?: return@launch
            viewModel.onFilePicked(picked.fileName, picked.content)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepIndicator(state.step.ordinalStep())

        val error = state.error
        when (val step = state.step) {
            // A failed read or parse lands back on PickFile; Retry opens the
            // picker again, so the error and its way out are one control (#73).
            is ImportStep.PickFile -> if (error != null) ErrorState(error, onRetry = ::pickFile) else PickFileStep(onPickFile = ::pickFile)

            is ImportStep.Resolving -> ResolvingStep(step)

            is ImportStep.Preview -> PreviewStep(step, onConfirm = viewModel::confirmApply, onCancel = viewModel::cancelPreview)

            is ImportStep.Applying -> ApplyingStep(step)

            is ImportStep.Summary -> SummaryStep(step.result, onImportAnother = viewModel::startOver, onDone = onDone)
        }
    }
}

private const val IMPORT_STEP_COUNT = 4

/** Which of the 4 user-visible steps (pick → preview → import → done) a state-machine step belongs to. */
private fun ImportStep.ordinalStep(): Int = when (this) {
    is ImportStep.PickFile -> 0
    is ImportStep.Resolving, is ImportStep.Preview -> 1
    is ImportStep.Applying -> 2
    is ImportStep.Summary -> 3
}

/** Four segments; amber up to and including the current step. */
@Composable
private fun StepIndicator(current: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(IMPORT_STEP_COUNT) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (index <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
            )
        }
    }
}

@Composable
private fun PickFileStep(onPickFile: () -> Unit) {
    EmptyState(
        icon = MuvissIcons.Import,
        title = "Import from another tracker",
        body = "Supports a Trakt export (JSON), a TV Time data export (CSV), or Muviss's own CSV format. See docs/IMPORT.md for details.",
        actionLabel = "Choose file",
        onAction = onPickFile,
    )
}

@Composable
private fun ResolvingStep(step: ImportStep.Resolving) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reading ${step.fileName}…", style = MaterialTheme.typography.titleMedium)
        ProgressRow(step.done, step.total, label = "Matching titles")
    }
}

@Composable
private fun ApplyingStep(step: ImportStep.Applying) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Importing…", style = MaterialTheme.typography.titleMedium)
        ProgressRow(step.done, step.total, label = "Saving titles")
    }
}

@Composable
private fun ProgressRow(done: Int, total: Int, label: String) {
    if (total <= 0) {
        CircularProgressIndicator()
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            Text("$label: $done of $total", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PreviewStep(step: ImportStep.Preview, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val preview = step.preview
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Preview: ${step.fileName}", style = MaterialTheme.typography.titleMedium)
        Text(preview.source.label(), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        SummaryCounts(
            titleCount = preview.titleCount,
            episodeCount = preview.episodeCount,
            unresolvedCount = preview.unresolvedCount,
        )
        if (preview.skippedRowCount > 0) {
            Text("${preview.skippedRowCount} row(s) in the file couldn't be read and were skipped.", style = MaterialTheme.typography.bodySmall)
        }
        if (preview.unresolved.isNotEmpty()) {
            Text("Unresolved titles", style = MaterialTheme.typography.titleSmall)
            UnresolvedList(preview.unresolved, modifier = Modifier.weight(1f, fill = false))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Import ${preview.resolved.size} title(s)") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
private fun SummaryCounts(titleCount: Int, episodeCount: Int, unresolvedCount: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(titleCount.toString(), "titles found", Modifier.weight(1f))
        StatTile(episodeCount.toString(), "episodes to mark", Modifier.weight(1f))
        StatTile(unresolvedCount.toString(), "unmatched", Modifier.weight(1f))
    }
}

@Composable
private fun UnresolvedList(unresolved: List<UnresolvedImportTitle>, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        items(unresolved, key = { it.title.displayTitle + it.reason }) { entry ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(entry.title.displayTitle, style = MaterialTheme.typography.bodyMedium)
                Text(entry.reason, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SummaryStep(result: ImportApplyResult, onImportAnother: () -> Unit, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            MuvissIcons.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(40.dp),
        )
        Text("Import complete", style = MaterialTheme.typography.titleMedium)
        Text("${result.importedTitleCount} title(s) imported", style = MaterialTheme.typography.bodyMedium)
        Text("${result.episodeTickCount} episode(s)/movie(s) marked watched", style = MaterialTheme.typography.bodyMedium)
        if (result.unresolved.isNotEmpty()) {
            Text("${result.unresolved.size} title(s) unresolved", style = MaterialTheme.typography.bodyMedium)
            UnresolvedList(result.unresolved, modifier = Modifier.weight(1f, fill = false))
        }
        if (result.failed.isNotEmpty()) {
            Text("${result.failed.size} title(s) failed to import", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(result.failed, key = { it.title.mediaId.toString() }) { failure ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(failure.title.title.displayTitle, style = MaterialTheme.typography.bodyMedium)
                        Text(failure.reason, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            OutlinedButton(onClick = onImportAnother, modifier = Modifier.fillMaxWidth()) { Text("Import another file") }
        }
    }
}

private fun ImportSource.label(): String = when (this) {
    ImportSource.TRAKT -> "Detected format: Trakt export"
    ImportSource.TV_TIME -> "Detected format: TV Time export"
    ImportSource.GENERIC_CSV -> "Detected format: generic CSV"
}
