package com.codingpit.muviss.feature.profile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.profile.domain.AccountChangeChoice
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import com.codingpit.muviss.feature.profile.domain.SyncCopy
import com.codingpit.muviss.feature.profile.domain.SyncProvider
import com.codingpit.muviss.feature.profile.domain.SyncStatusDetail
import com.codingpit.muviss.feature.profile.ui.generated.resources.Res
import com.codingpit.muviss.feature.profile.ui.generated.resources.account
import com.codingpit.muviss.feature.profile.ui.generated.resources.account_change_body
import com.codingpit.muviss.feature.profile.ui.generated.resources.account_change_body_no_email
import com.codingpit.muviss.feature.profile.ui.generated.resources.account_change_choose
import com.codingpit.muviss.feature.profile.ui.generated.resources.account_change_consequences
import com.codingpit.muviss.feature.profile.ui.generated.resources.account_change_later
import com.codingpit.muviss.feature.profile.ui.generated.resources.account_change_merge
import com.codingpit.muviss.feature.profile.ui.generated.resources.account_change_merge_no_email
import com.codingpit.muviss.feature.profile.ui.generated.resources.account_change_replace
import com.codingpit.muviss.feature.profile.ui.generated.resources.account_change_replace_no_email
import com.codingpit.muviss.feature.profile.ui.generated.resources.account_change_title
import com.codingpit.muviss.feature.profile.ui.generated.resources.action_cancel
import com.codingpit.muviss.feature.profile.ui.generated.resources.action_retry
import com.codingpit.muviss.feature.profile.ui.generated.resources.resync
import com.codingpit.muviss.feature.profile.ui.generated.resources.resync_body
import com.codingpit.muviss.feature.profile.ui.generated.resources.resync_everything
import com.codingpit.muviss.feature.profile.ui.generated.resources.resync_question
import com.codingpit.muviss.feature.profile.ui.generated.resources.sign_in_with
import com.codingpit.muviss.feature.profile.ui.generated.resources.sign_out
import com.codingpit.muviss.feature.profile.ui.generated.resources.signed_in
import com.codingpit.muviss.feature.profile.ui.generated.resources.state_off
import com.codingpit.muviss.feature.profile.ui.generated.resources.state_on
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_automatically
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_local_only
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_now
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_pitch
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_title
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_unlock
import com.codingpit.muviss.feature.profile.ui.generated.resources.syncing
import org.jetbrains.compose.resources.stringResource

/** Test tags, public because `:app:shared`'s full-flow test drives this screen from outside the module. */
const val SYNC_SECTION_TAG = "sync-section"
const val SYNC_LAST_SYNCED_TAG = "sync-last-synced"
const val SYNC_DETAIL_TAG = "sync-status-detail"
const val SYNC_SESSION_EXPIRED_TAG = "sync-session-expired"
const val SYNC_AUTOMATIC_SWITCH_TAG = "sync-automatic-switch"
const val SYNC_AUTOMATIC_DESCRIPTION_TAG = "sync-automatic-description"
const val SYNC_NOW_TAG = "sync-now"
const val SYNC_RETRY_TAG = "sync-retry"
const val SYNC_RESYNC_TAG = "sync-resync-everything"
const val SYNC_SIGN_OUT_TAG = "sync-sign-out"
const val SYNC_UNLOCK_TAG = "sync-unlock"
const val SYNC_RESYNC_DIALOG_TAG = "sync-resync-dialog"
const val SYNC_RESYNC_CONFIRM_TAG = "sync-resync-confirm"
const val SYNC_RESYNC_CANCEL_TAG = "sync-resync-cancel"
const val SYNC_ACCOUNT_DIALOG_TAG = "sync-account-dialog"
const val SYNC_ACCOUNT_REPLACE_TAG = "sync-account-replace"
const val SYNC_ACCOUNT_MERGE_TAG = "sync-account-merge"
const val SYNC_ACCOUNT_LATER_TAG = "sync-account-later"
const val SYNC_ACCOUNT_CHOOSE_TAG = "sync-account-choose"

fun syncSignInTag(provider: SyncProvider) = "sync-sign-in-${provider.name.lowercase()}"

/** Everything the sync section can ask of its host, so the composable itself stays a function of [SyncUiState]. */
@Suppress("LongParameterList") // one callback per thing the section can be asked to do, every one defaulted so a test names only what it drives
internal class SyncSectionActions(
    val onSignInClicked: (SyncProvider) -> Unit = {},
    val onSyncNowClicked: () -> Unit = {},
    val onSignOutClicked: () -> Unit = {},
    val onUnlockClicked: () -> Unit = {},
    val onAutomaticSyncToggled: (Boolean) -> Unit = {},
    val onResyncEverythingRequested: () -> Unit = {},
    val onResyncEverythingConfirmed: () -> Unit = {},
    val onResyncEverythingDismissed: () -> Unit = {},
    val onAccountChoiceMade: (AccountChangeChoice) -> Unit = {},
    val onAccountChoiceDeferred: () -> Unit = {},
    val onAccountChoiceRequested: () -> Unit = {},
)

/**
 * Account/sync section (EPIC 9, extended by EPIC 40). Renders nothing when sync
 * isn't configured for this build ([SyncAccountState.Unavailable]) — the entry
 * point is hidden entirely rather than shown disabled, same contract as a
 * blank Sentry DSN (CLAUDE.md).
 *
 * [SyncAccountState.Locked] is the deliberate opposite (ADR 0018): the build
 * has sync, the user has not bought it, so the row appears and offers the
 * purchase. Hiding it would leave a paid feature undiscoverable.
 *
 * The "Sync automatically" switch follows the same two rules at a second
 * level (ADR 0021): absent when the build has no background sync, present but
 * disabled until there is a session and an entitlement to use it with.
 */
@Composable
internal fun SyncSection(sync: SyncUiState, actions: SyncSectionActions, modifier: Modifier = Modifier) {
    val account = sync.account
    if (account == SyncAccountState.Unavailable) return

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().testTag(SYNC_SECTION_TAG),
    ) {
        Row(
            Modifier.padding(MuvissSpacing.l),
            horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                MuvissIcons.Account,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
                AccountContent(sync, account, actions)
                if (sync.automaticSyncAvailable) AutomaticSyncRow(sync, actions)
                if (account is SyncAccountState.SignedIn) ResyncRow(sync, actions)
            }
        }
    }

    if (sync.confirmingResync) ResyncDialog(actions)
    if (sync.choosingAccountLibrary && account is SyncAccountState.SignedIn) AccountChangeDialog(account.email, actions)
}

@Composable
private fun AccountContent(sync: SyncUiState, account: SyncAccountState, actions: SyncSectionActions) {
    when (account) {
        SyncAccountState.Unavailable -> Unit

        is SyncAccountState.Locked -> {
            Text(stringResource(Res.string.sync_title), style = MaterialTheme.typography.titleSmall)
            Hint(stringResource(Res.string.sync_pitch))
            Row(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = actions.onUnlockClicked, modifier = Modifier.testTag(SYNC_UNLOCK_TAG)) { Text(stringResource(Res.string.sync_unlock)) }
                // A lapsed subscriber is still signed in; without this
                // the paywall would be the only thing they can reach.
                if (account.email != null) {
                    TextButton(onClick = actions.onSignOutClicked, enabled = !sync.syncing, modifier = Modifier.testTag(SYNC_SIGN_OUT_TAG)) { Text(stringResource(Res.string.sign_out)) }
                }
            }
        }

        SyncAccountState.SignedOut -> SignedOutContent(sync, actions, expired = false)

        SyncAccountState.SessionExpired -> SignedOutContent(sync, actions, expired = true)

        is SyncAccountState.SignedIn -> SignedInContent(sync, account, actions)
    }
}

@Composable
private fun SignedOutContent(sync: SyncUiState, actions: SyncSectionActions, expired: Boolean) {
    Text(stringResource(Res.string.account), style = MaterialTheme.typography.titleSmall)
    if (expired) {
        // A session that died is not the same as never having signed in, and a
        // silent stop would look exactly like it.
        Text(
            SyncCopy.SESSION_EXPIRED,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SYNC_SESSION_EXPIRED_TAG),
        )
    } else {
        Hint(stringResource(Res.string.sync_local_only))
    }
    // One button per provider rather than a picker: there are
    // two at most, and a picker would add a step to the one
    // action on this row.
    Row(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
        sync.providers.forEach { provider ->
            OutlinedButton(
                onClick = { actions.onSignInClicked(provider) },
                enabled = !sync.syncing,
                modifier = Modifier.testTag(syncSignInTag(provider)),
            ) {
                Text(stringResource(Res.string.sign_in_with, provider.displayName))
            }
        }
    }
}

@Composable
private fun SignedInContent(sync: SyncUiState, account: SyncAccountState.SignedIn, actions: SyncSectionActions) {
    Text(account.email ?: stringResource(Res.string.signed_in), style = MaterialTheme.typography.titleSmall)
    Hint(sync.lastSyncedLabel, Modifier.testTag(SYNC_LAST_SYNCED_TAG))
    sync.statusDetail?.let { StatusDetail(it, actions, enabled = !sync.syncing) }
    Row(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = actions.onSyncNowClicked, enabled = !sync.syncing, modifier = Modifier.testTag(SYNC_NOW_TAG)) {
            Icon(MuvissIcons.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(" " + stringResource(if (sync.syncing) Res.string.syncing else Res.string.sync_now))
        }
        TextButton(onClick = actions.onSignOutClicked, enabled = !sync.syncing, modifier = Modifier.testTag(SYNC_SIGN_OUT_TAG)) { Text(stringResource(Res.string.sign_out)) }
    }
}

@Composable
private fun StatusDetail(detail: SyncStatusDetail, actions: SyncSectionActions, enabled: Boolean) {
    val failed = detail is SyncStatusDetail.Failed
    Row(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s), verticalAlignment = Alignment.CenterVertically) {
        Text(
            SyncCopy.detail(detail),
            style = MaterialTheme.typography.bodySmall,
            color = if (failed || detail == SyncStatusDetail.AccountChanged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false).testTag(SYNC_DETAIL_TAG),
        )
        if (failed) {
            TextButton(onClick = actions.onSyncNowClicked, enabled = enabled, modifier = Modifier.testTag(SYNC_RETRY_TAG)) { Text(stringResource(Res.string.action_retry)) }
        }
        // Retrying cannot fix a mismatch; only an answer can.
        if (detail == SyncStatusDetail.AccountChanged) {
            TextButton(onClick = actions.onAccountChoiceRequested, enabled = enabled, modifier = Modifier.testTag(SYNC_ACCOUNT_CHOOSE_TAG)) {
                Text(stringResource(Res.string.account_change_choose))
            }
        }
    }
}

/**
 * The switch. The whole row is the toggle target and the [Switch] inside it is
 * inert, which is the pattern that gives TalkBack one focus stop reading
 * label, state and description together. [stateDescription] says "On"/"Off"
 * explicitly because the default wording differs by platform.
 */
@Composable
private fun AutomaticSyncRow(sync: SyncUiState, actions: SyncSectionActions) {
    val enabled = sync.automaticSyncEnabled
    val label = stringResource(Res.string.sync_automatically)
    val on = stringResource(Res.string.state_on)
    val off = stringResource(Res.string.state_off)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = MuvissSpacing.s)
            .testTag(SYNC_AUTOMATIC_SWITCH_TAG)
            .toggleable(
                value = sync.automaticSync,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = actions.onAutomaticSyncToggled,
            )
            .semantics {
                contentDescription = label
                stateDescription = if (sync.automaticSync) on else off
            },
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                SyncCopy.automaticSyncDescription(sync.automaticSyncMode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(SYNC_AUTOMATIC_DESCRIPTION_TAG),
            )
        }
        Switch(checked = sync.automaticSync, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ResyncRow(sync: SyncUiState, actions: SyncSectionActions) {
    TextButton(
        onClick = actions.onResyncEverythingRequested,
        enabled = !sync.syncing,
        modifier = Modifier.testTag(SYNC_RESYNC_TAG),
    ) { Text(stringResource(Res.string.resync_everything)) }
}

@Composable
private fun ResyncDialog(actions: SyncSectionActions) {
    AlertDialog(
        onDismissRequest = actions.onResyncEverythingDismissed,
        modifier = Modifier.testTag(SYNC_RESYNC_DIALOG_TAG),
        title = { Text(stringResource(Res.string.resync_question)) },
        text = {
            Text(
                stringResource(Res.string.resync_body),
            )
        },
        confirmButton = {
            TextButton(onClick = actions.onResyncEverythingConfirmed, modifier = Modifier.testTag(SYNC_RESYNC_CONFIRM_TAG)) { Text(stringResource(Res.string.resync)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onResyncEverythingDismissed, modifier = Modifier.testTag(SYNC_RESYNC_CANCEL_TAG)) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/**
 * Which library this device keeps, now that a different account is signed in
 * (#148, the ADR 0019 decision on #75). Blocking: a tap outside does not close
 * it, so it cannot be brushed away by accident. Closing it on purpose ("Decide
 * later", Back, Escape) chooses nothing, and nothing syncs: the engine keeps
 * answering every trigger with an account mismatch until one of the two
 * buttons is pressed.
 *
 * Three stacked buttons rather than AlertDialog's row: both choices name the
 * account, and an email is too long to share a row with anything.
 * "Replace" is the filled one, because it is the default.
 */
@Composable
private fun AccountChangeDialog(email: String?, actions: SyncSectionActions) {
    AlertDialog(
        onDismissRequest = actions.onAccountChoiceDeferred,
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.testTag(SYNC_ACCOUNT_DIALOG_TAG),
        title = { Text(stringResource(Res.string.account_change_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                Text(if (email != null) stringResource(Res.string.account_change_body, email) else stringResource(Res.string.account_change_body_no_email))
                Text(stringResource(Res.string.account_change_consequences))
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
                Button(
                    onClick = { actions.onAccountChoiceMade(AccountChangeChoice.ReplaceWithAccountLibrary) },
                    modifier = Modifier.fillMaxWidth().testTag(SYNC_ACCOUNT_REPLACE_TAG),
                ) {
                    Text(if (email != null) stringResource(Res.string.account_change_replace, email) else stringResource(Res.string.account_change_replace_no_email))
                }
                OutlinedButton(
                    onClick = { actions.onAccountChoiceMade(AccountChangeChoice.AddDeviceLibraryToAccount) },
                    modifier = Modifier.fillMaxWidth().testTag(SYNC_ACCOUNT_MERGE_TAG),
                ) {
                    Text(if (email != null) stringResource(Res.string.account_change_merge, email) else stringResource(Res.string.account_change_merge_no_email))
                }
                TextButton(onClick = actions.onAccountChoiceDeferred, modifier = Modifier.fillMaxWidth().testTag(SYNC_ACCOUNT_LATER_TAG)) {
                    Text(stringResource(Res.string.account_change_later))
                }
            }
        },
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = MuvissSpacing.xs),
    )
}
