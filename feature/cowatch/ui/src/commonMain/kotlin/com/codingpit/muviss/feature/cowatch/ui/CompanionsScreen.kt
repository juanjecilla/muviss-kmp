package com.codingpit.muviss.feature.cowatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.cowatch.api.CompanionState
import com.codingpit.muviss.feature.cowatch.api.LinkedCompanion

/**
 * Linking, naming and unlinking Companions, plus what this account publishes.
 *
 * Reached from Profile, beside the sync row, because that is where accounts
 * already live and where the entitlement gate is already explained.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionsScreen(
    viewModel: CompanionsViewModel,
    onBack: () -> Unit,
    onOpenShortlist: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pasted by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watch together") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(MuvissSpacing.l),
            verticalArrangement = Arrangement.spacedBy(MuvissSpacing.l),
        ) {
            Text(
                "Link with someone and Muviss will work out what you could watch together. " +
                    "Only the titles you'd watch with them are shared — never your watch history.",
                style = MaterialTheme.typography.bodyMedium,
            )

            state.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = viewModel::dismissError) { Text("Dismiss") }
            }

            InviteCard(code = state.myInviteCode, onCreate = viewModel::createInvite, onDismiss = viewModel::dismissInvite)

            Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                Text("Have a code?", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text("Paste their code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(PASTE_CODE_FIELD_TAG),
                )
                TextButton(
                    enabled = pasted.isNotBlank(),
                    onClick = {
                        viewModel.acceptInvite(pasted)
                        pasted = ""
                    },
                ) { Text("Link") }
            }

            state.companions.forEach { companion ->
                CompanionCard(
                    companion = companion,
                    onOpenShortlist = { onOpenShortlist(companion.userId) },
                    onConfirm = { viewModel.confirm(companion.userId) },
                    onRename = { viewModel.rename(companion.userId, it) },
                    onUnlink = { viewModel.unlink(companion.userId) },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(FRACTION_FOR_LABEL)) {
                    Text("Include things you've seen", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Titles you've already watched can be suggested for a rewatch, unless you've said otherwise for one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.settings.includeSeenByDefault,
                    onCheckedChange = viewModel::setIncludeSeenByDefault,
                )
            }
        }
    }
}

@Composable
private fun InviteCard(code: String?, onCreate: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MuvissSpacing.l),
            verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
        ) {
            Text("Invite someone", style = MaterialTheme.typography.titleMedium)
            if (code == null) {
                Text(
                    "Send them a code. They paste it here, you both confirm, and nothing is shared until you do.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCreate) { Text("Create a code") }
            } else {
                // Shown in full rather than truncated: the person has to be able
                // to copy all of it, and it carries nothing secret (ADR 0022).
                Text(code, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun CompanionCard(
    companion: LinkedCompanion,
    onOpenShortlist: () -> Unit,
    onConfirm: () -> Unit,
    onRename: (String?) -> Unit,
    onUnlink: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var name by remember(companion.localName) { mutableStateOf(companion.localName.orEmpty()) }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MuvissSpacing.l),
            verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
        ) {
            // Their own name is not available and never will be: nothing in this
            // app can name another account, so each side names the other for
            // themselves, on their own device (ADR 0022).
            Text(companion.localName ?: "Someone", style = MaterialTheme.typography.titleMedium)
            Text(
                text = describe(companion.state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (editing) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Call them") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(RENAME_FIELD_TAG),
                )
                TextButton(onClick = {
                    onRename(name.ifBlank { null })
                    editing = false
                }) { Text("Save") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                    if (companion.state == CompanionState.ACTIVE) {
                        TextButton(onClick = onOpenShortlist) { Text("What to watch") }
                    }
                    if (companion.state == CompanionState.AWAITING_CONFIRMATION) {
                        TextButton(onClick = onConfirm) { Text("Confirm") }
                    }
                    TextButton(onClick = { editing = true }) { Text("Rename") }
                    TextButton(onClick = onUnlink) { Text("Unlink") }
                }
            }
        }
    }
}

private fun describe(state: CompanionState): String = when (state) {
    CompanionState.INVITED -> "Waiting for them to accept."

    CompanionState.AWAITING_CONFIRMATION -> "They're ready — confirm to start sharing."

    CompanionState.ACTIVE -> "Linked."

    // Says what unlinking can and cannot do, rather than implying a reach it
    // does not have (ADR 0022).
    CompanionState.REVOKED -> "Unlinked. They may keep a copy until their app next syncs."
}

private const val FRACTION_FOR_LABEL = 0.8f

/** Identifies the paste-a-code field, for tests. */
const val PASTE_CODE_FIELD_TAG = "companions-paste-code-field"

/** Identifies a Companion's rename field, for tests. */
const val RENAME_FIELD_TAG = "companions-rename-field"
