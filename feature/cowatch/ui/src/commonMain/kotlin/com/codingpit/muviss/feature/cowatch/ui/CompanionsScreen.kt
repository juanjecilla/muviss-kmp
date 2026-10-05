package com.codingpit.muviss.feature.cowatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.share.TextSharer
import com.codingpit.muviss.core.designsystem.share.rememberTextSharer
import com.codingpit.muviss.core.designsystem.text.resolve
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.cowatch.api.CompanionState
import com.codingpit.muviss.feature.cowatch.api.LinkedCompanion
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.Res
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.action_back
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.action_dismiss
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.action_save
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.companion_confirm
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.companion_default_name
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.companion_rename
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.companion_rename_field
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.companion_state_active
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.companion_state_awaiting
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.companion_state_invited
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.companion_state_revoked
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.companion_unlink
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.companion_what_to_watch
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.companions_intro
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.cowatch_title
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.include_seen_body
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.include_seen_title
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.invite_copied
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.invite_copy
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.invite_create
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.invite_done
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.invite_explainer
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.invite_share
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.invite_share_message
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.invite_title
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.redeem_action
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.redeem_field
import com.codingpit.muviss.feature.cowatch.ui.generated.resources.redeem_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

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
    textSharer: TextSharer = rememberTextSharer(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pasted by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(Res.string.invite_copied)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.cowatch_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(Res.string.action_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(MuvissSpacing.l),
            verticalArrangement = Arrangement.spacedBy(MuvissSpacing.l),
        ) {
            Text(stringResource(Res.string.companions_intro), style = MaterialTheme.typography.bodyMedium)

            state.error?.let { message ->
                Text(message.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = viewModel::dismissError) { Text(stringResource(Res.string.action_dismiss)) }
            }

            InviteCard(
                code = state.myInviteCode,
                canShare = textSharer.canShare,
                onCreate = viewModel::createInvite,
                onCopy = { code ->
                    textSharer.copy(code)
                    if (!textSharer.confirmsCopy) scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                },
                onShare = textSharer::share,
                onDismiss = viewModel::dismissInvite,
            )

            Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                Text(stringResource(Res.string.redeem_title), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text(stringResource(Res.string.redeem_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(PASTE_CODE_FIELD_TAG),
                )
                TextButton(
                    enabled = pasted.isNotBlank(),
                    onClick = {
                        viewModel.acceptInvite(pasted)
                        pasted = ""
                    },
                ) { Text(stringResource(Res.string.redeem_action)) }
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

            // The label takes what the switch leaves, with a gap: a fixed
            // fraction let a long (or translated) subtitle run under it.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.include_seen_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(Res.string.include_seen_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(MuvissSpacing.m))
                Switch(
                    checked = state.settings.includeSeenByDefault,
                    onCheckedChange = viewModel::setIncludeSeenByDefault,
                )
            }
        }
    }
}

@Composable
private fun InviteCard(
    code: String?,
    canShare: Boolean,
    onCreate: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MuvissSpacing.l),
            verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
        ) {
            Text(stringResource(Res.string.invite_title), style = MaterialTheme.typography.titleMedium)
            if (code == null) {
                Text(
                    stringResource(Res.string.invite_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCreate) { Text(stringResource(Res.string.invite_create)) }
            } else {
                // Shown in full rather than truncated: the person has to be able
                // to copy all of it, and it carries nothing secret (ADR 0022).
                Text(code, style = MaterialTheme.typography.bodyMedium)
                // The message is in the sender's language: it is read by
                // someone who may not have the app yet, so it says where to
                // paste the code rather than sending a bare uuid.nonce.
                val shareMessage = stringResource(Res.string.invite_share_message, code)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                    TextButton(onClick = { onCopy(code) }) { Text(stringResource(Res.string.invite_copy)) }
                    if (canShare) {
                        TextButton(onClick = { onShare(shareMessage) }) { Text(stringResource(Res.string.invite_share)) }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.invite_done)) }
                }
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
            Text(
                companion.localName ?: stringResource(Res.string.companion_default_name),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(describe(companion.state)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (editing) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.companion_rename_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(RENAME_FIELD_TAG),
                )
                TextButton(onClick = {
                    onRename(name.ifBlank { null })
                    editing = false
                }) { Text(stringResource(Res.string.action_save)) }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                    if (companion.state == CompanionState.ACTIVE) {
                        TextButton(onClick = onOpenShortlist) { Text(stringResource(Res.string.companion_what_to_watch)) }
                    }
                    if (companion.state == CompanionState.AWAITING_CONFIRMATION) {
                        TextButton(onClick = onConfirm) { Text(stringResource(Res.string.companion_confirm)) }
                    }
                    TextButton(onClick = { editing = true }) { Text(stringResource(Res.string.companion_rename)) }
                    TextButton(onClick = onUnlink) { Text(stringResource(Res.string.companion_unlink)) }
                }
            }
        }
    }
}

private fun describe(state: CompanionState): StringResource = when (state) {
    CompanionState.INVITED -> Res.string.companion_state_invited

    CompanionState.AWAITING_CONFIRMATION -> Res.string.companion_state_awaiting

    CompanionState.ACTIVE -> Res.string.companion_state_active

    // Says what unlinking can and cannot do, rather than implying a reach it
    // does not have (ADR 0022).
    CompanionState.REVOKED -> Res.string.companion_state_revoked
}

/** Identifies the paste-a-code field, for tests. */
const val PASTE_CODE_FIELD_TAG = "companions-paste-code-field"

/** Identifies a Companion's rename field, for tests. */
const val RENAME_FIELD_TAG = "companions-rename-field"
