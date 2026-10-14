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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.codingpit.muviss.core.designsystem.text.resolve
import com.codingpit.muviss.core.designsystem.text.resolveAsync
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.collection.domain.MediaList
import com.codingpit.muviss.feature.collection.ui.generated.resources.Res
import com.codingpit.muviss.feature.collection.ui.generated.resources.action_cancel
import com.codingpit.muviss.feature.collection.ui.generated.resources.action_create
import com.codingpit.muviss.feature.collection.ui.generated.resources.action_rename
import com.codingpit.muviss.feature.collection.ui.generated.resources.action_save
import com.codingpit.muviss.feature.collection.ui.generated.resources.action_undo
import com.codingpit.muviss.feature.collection.ui.generated.resources.delete_list
import com.codingpit.muviss.feature.collection.ui.generated.resources.list_deleted
import com.codingpit.muviss.feature.collection.ui.generated.resources.list_name_placeholder
import com.codingpit.muviss.feature.collection.ui.generated.resources.list_titles
import com.codingpit.muviss.feature.collection.ui.generated.resources.lists_empty_body
import com.codingpit.muviss.feature.collection.ui.generated.resources.lists_empty_title
import com.codingpit.muviss.feature.collection.ui.generated.resources.new_list
import com.codingpit.muviss.feature.collection.ui.generated.resources.rename_list
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
    val snackbarHostState = remember { SnackbarHostState() }

    // Same shape as Progress's tick: the change shows at once and the
    // snackbar's Undo takes it back (#73).
    // The delete is already saved; nothing is lost if this effect is cancelled
    // with the screen. The captured `deleted` is what Undo restores, so a
    // second delete can never make this snackbar restore the wrong list.
    LaunchedEffect(state.lastDeleted) {
        val deleted = state.lastDeleted ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = getString(Res.string.list_deleted, deleted.list.name),
            actionLabel = getString(Res.string.action_undo),
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(deleted) else viewModel.deleteUndoDismissed(deleted)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.resolveAsync())
        viewModel.consumeMessage()
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(top = MuvissSpacing.xxl))
                state.error != null -> ErrorState(state.error?.resolve().orEmpty(), onRetry = viewModel::retry)
                state.lists.isEmpty() -> EmptyListsState()
                else -> ListsColumn(state.lists, onOpenList, onEdit = viewModel::startEditing, onDelete = viewModel::deleteList)
            }
        }
        ExtendedFloatingActionButton(
            onClick = viewModel::startCreating,
            icon = { Icon(MuvissIcons.Add, contentDescription = null) },
            text = { Text(stringResource(Res.string.new_list)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(MuvissSpacing.l),
        )
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    if (state.creating) {
        ListNameDialog(
            title = stringResource(Res.string.new_list),
            initialName = "",
            confirmLabel = stringResource(Res.string.action_create),
            error = state.dialogError?.resolve(),
            onConfirm = viewModel::createList,
            onDismiss = viewModel::cancelCreating,
        )
    }

    state.editing?.let { editing ->
        ListNameDialog(
            title = stringResource(Res.string.rename_list),
            initialName = editing.name,
            confirmLabel = stringResource(Res.string.action_save),
            error = state.dialogError?.resolve(),
            onConfirm = viewModel::renameList,
            onDismiss = viewModel::cancelEditing,
        )
    }
}

@Composable
private fun EmptyListsState() {
    EmptyState(
        icon = MuvissIcons.AddToList,
        title = stringResource(Res.string.lists_empty_title),
        body = stringResource(Res.string.lists_empty_body),
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
                    pluralStringResource(Res.plurals.list_titles, list.entryCount, list.entryCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) { Text(stringResource(Res.string.action_rename)) }
            IconButton(onClick = onDelete) {
                Icon(MuvissIcons.Close, contentDescription = stringResource(Res.string.delete_list, list.name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ListNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.list_name_placeholder)) },
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
