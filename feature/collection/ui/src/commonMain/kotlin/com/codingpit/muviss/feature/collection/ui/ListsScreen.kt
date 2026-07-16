package com.codingpit.muviss.feature.collection.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.feature.collection.domain.MediaList

/**
 * The Lists segment of the Collection screen (EPIC 17): every user-defined
 * list with its live entry count, create/rename/delete, and a tap-through
 * to that list's contents ([onOpenList]).
 */
@Composable
fun ListsScreen(
    viewModel: ListsViewModel,
    onOpenList: (MediaList) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = viewModel::startCreating) { Text("New list") }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(top = 32.dp))
                state.error != null -> Text(state.error!!, modifier = Modifier.padding(top = 32.dp), style = MaterialTheme.typography.bodyMedium)
                state.lists.isEmpty() -> EmptyListsState()
                else -> ListsColumn(state.lists, onOpenList, onEdit = viewModel::startEditing, onDelete = viewModel::deleteList)
            }
        }
    }

    if (state.creating) {
        ListNameDialog(
            title = "New list",
            initialName = "",
            confirmLabel = "Create",
            onConfirm = viewModel::createList,
            onDismiss = viewModel::cancelCreating,
        )
    }

    state.editing?.let { editing ->
        ListNameDialog(
            title = "Rename list",
            initialName = editing.name,
            confirmLabel = "Save",
            onConfirm = viewModel::renameList,
            onDismiss = viewModel::cancelEditing,
        )
    }
}

@Composable
private fun EmptyListsState() {
    Text(
        "No lists yet — tap \"New list\" to start one, like \"Marathon 2026\".",
        modifier = Modifier.padding(top = 32.dp, start = 24.dp, end = 24.dp),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ListsColumn(
    lists: List<MediaList>,
    onOpenList: (MediaList) -> Unit,
    onEdit: (MediaList) -> Unit,
    onDelete: (MediaList) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        items(lists, key = { it.id }) { list ->
            ListRow(list, onClick = { onOpenList(list) }, onEdit = { onEdit(list) }, onDelete = { onDelete(list) })
        }
    }
}

@Composable
private fun ListRow(
    list: MediaList,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(onClick = onClick).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(list.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (list.entryCount == 1) "1 title" else "${list.entryCount} titles",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onEdit) { Text("Rename") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun ListNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("List name") })
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
