package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.feature.collection.api.ListSummary
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.action_create
import com.codingpit.muviss.feature.search.ui.generated.resources.action_done
import com.codingpit.muviss.feature.search.ui.generated.resources.add_to_list
import com.codingpit.muviss.feature.search.ui.generated.resources.list_with_count
import com.codingpit.muviss.feature.search.ui.generated.resources.new_list_name
import org.jetbrains.compose.resources.stringResource

/**
 * Detail's "Add to list" affordance (EPIC 17): every list with a checkmark
 * for membership, toggled directly (no separate Save step — each tap
 * round-trips through [AddToListViewModel] immediately, same as
 * [DetailScreen]'s favorite/mute buttons), plus an inline "create new list
 * and add" row so the user never has to leave this dialog to start one.
 */
@Composable
fun AddToListDialog(viewModel: AddToListViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var newListName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_to_list)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(modifier = Modifier.height(200.dp)) {
                    items(state.lists, key = ListSummary::id) { list ->
                        ListMembershipRow(
                            list = list,
                            checked = list.id in state.memberListIds,
                            onToggle = { viewModel.toggle(list.id) },
                        )
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newListName,
                        onValueChange = { newListName = it },
                        placeholder = { Text(stringResource(Res.string.new_list_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            viewModel.createAndAdd(newListName)
                            newListName = ""
                        },
                        enabled = newListName.isNotBlank(),
                    ) { Text(stringResource(Res.string.action_create)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_done)) }
        },
    )
}

@Composable
private fun ListMembershipRow(list: ListSummary, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(stringResource(Res.string.list_with_count, list.name, list.entryCount), style = MaterialTheme.typography.bodyMedium)
    }
}
