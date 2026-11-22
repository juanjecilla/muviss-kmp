package com.codingpit.muviss.feature.nextpick.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.Res
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_sheet_body
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_sheet_not_now
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_sheet_title
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_sheet_whats_next
import com.codingpit.muviss.models.MediaId
import org.jetbrains.compose.resources.stringResource

/**
 * The non-blocking "What's next?" sheet (EPIC 51, #400). Dismissible by a swipe, a scrim tap, or "Not now";
 * all three spend the day's prompt, which [CompletionPromptViewModel] already recorded when it appeared.
 * Hosted once at the app root, above the NavHost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompletionSheetHost(viewModel: CompletionPromptViewModel, onWhatsNext: (anchor: MediaId?) -> Unit) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    val anchor by viewModel.anchor.collectAsStateWithLifecycle()
    if (!visible) return
    ModalBottomSheet(onDismissRequest = viewModel::dismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(stringResource(Res.string.nextpick_sheet_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.nextpick_sheet_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    viewModel.dismiss()
                    onWhatsNext(anchor)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.nextpick_sheet_whats_next)) }
            TextButton(onClick = viewModel::dismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.nextpick_sheet_not_now))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
