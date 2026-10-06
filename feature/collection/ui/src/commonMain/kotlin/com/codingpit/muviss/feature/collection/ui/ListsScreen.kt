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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
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

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(top = MuvissSpacing.xxl))
                state.error != null -> ErrorState(state.error.orEmpty(), onRetry = viewModel::retry)
                state.lists.isEmpty() -> EmptyListsState()
                else -> ListsColumn(state.lists, onOpenList, onEdit = viewModel::startEditing, onDelete = viewModel::deleteList)
            }
        }
        ExtendedFloatingActionButton(
            onClick = viewModel::startCreating,
            icon = { Icon(MuvissIcons.Add, contentDescription = null) },
            text = { Text("New list") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(MuvissSpacing.l),
        )
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
    EmptyState(
        icon = MuvissIcons.AddToList,
        title = "No lists yet",
        body = "Tap \"New list\" to start one, like \"Marathon 2026\".",
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
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
        contentPadding = PaddingValues(
            start = MuvissSpacing.m,
            end = MuvissSpacing.m,
            top = MuvissSpacing.m,
            bottom = MuvissSpacing.bottomContent,
        ),
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
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.clickable(onClick = onClick).padding(horizontal = MuvissSpacing.l, vertical = MuvissSpacing.m),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(list.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (list.entryCount == 1) "1 title" else "${list.entryCount} titles",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) { Text("Rename") }
            IconButton(onClick = onDelete) {
                Icon(MuvissIcons.Close, contentDescription = "Delete ${list.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
