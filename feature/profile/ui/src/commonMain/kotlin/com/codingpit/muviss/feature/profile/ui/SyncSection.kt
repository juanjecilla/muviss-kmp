package com.codingpit.muviss.feature.profile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
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
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import com.codingpit.muviss.feature.profile.domain.SyncCopy
import com.codingpit.muviss.feature.profile.domain.SyncProvider
import com.codingpit.muviss.feature.profile.domain.SyncStatusDetail

internal const val SYNC_SECTION_TAG = "sync-section"
internal const val SYNC_LAST_SYNCED_TAG = "sync-last-synced"
internal const val SYNC_DETAIL_TAG = "sync-status-detail"
internal const val SYNC_SESSION_EXPIRED_TAG = "sync-session-expired"
internal const val SYNC_AUTOMATIC_SWITCH_TAG = "sync-automatic-switch"
internal const val SYNC_AUTOMATIC_DESCRIPTION_TAG = "sync-automatic-description"
internal const val SYNC_NOW_TAG = "sync-now"
internal const val SYNC_RETRY_TAG = "sync-retry"
internal const val SYNC_RESYNC_TAG = "sync-resync-everything"
internal const val SYNC_SIGN_OUT_TAG = "sync-sign-out"
internal const val SYNC_UNLOCK_TAG = "sync-unlock"
internal const val SYNC_RESYNC_DIALOG_TAG = "sync-resync-dialog"
internal const val SYNC_RESYNC_CONFIRM_TAG = "sync-resync-confirm"
internal const val SYNC_RESYNC_CANCEL_TAG = "sync-resync-cancel"

internal fun syncSignInTag(provider: SyncProvider) = "sync-sign-in-${provider.name.lowercase()}"

internal const val AUTOMATIC_SYNC_LABEL = "Sync automatically"

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
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                MuvissIcons.Account,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AccountContent(sync, account, actions)
                if (sync.automaticSyncAvailable) AutomaticSyncRow(sync, actions)
                if (account is SyncAccountState.SignedIn) ResyncRow(sync, actions)
            }
        }
    }

    if (sync.confirmingResync) ResyncDialog(actions)
}

@Composable
private fun AccountContent(sync: SyncUiState, account: SyncAccountState, actions: SyncSectionActions) {
    when (account) {
        SyncAccountState.Unavailable -> Unit

        is SyncAccountState.Locked -> {
            Text("Sync across devices", style = MaterialTheme.typography.titleSmall)
            Hint("Keep your library, progress and rewatch history on every device.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = actions.onUnlockClicked, modifier = Modifier.testTag(SYNC_UNLOCK_TAG)) { Text("Unlock sync") }
                // A lapsed subscriber is still signed in; without this
                // the paywall would be the only thing they can reach.
                if (account.email != null) {
                    TextButton(onClick = actions.onSignOutClicked, enabled = !sync.syncing, modifier = Modifier.testTag(SYNC_SIGN_OUT_TAG)) { Text("Sign out") }
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
    Text("Account", style = MaterialTheme.typography.titleSmall)
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
        Hint("Everything stays on this device today.")
    }
    // One button per provider rather than a picker: there are
    // two at most, and a picker would add a step to the one
    // action on this row.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sync.providers.forEach { provider ->
            OutlinedButton(
                onClick = { actions.onSignInClicked(provider) },
                enabled = !sync.syncing,
                modifier = Modifier.testTag(syncSignInTag(provider)),
            ) {
                Text("Sign in with ${provider.displayName}")
            }
        }
    }
}

@Composable
private fun SignedInContent(sync: SyncUiState, account: SyncAccountState.SignedIn, actions: SyncSectionActions) {
    Text(account.email ?: "Signed in", style = MaterialTheme.typography.titleSmall)
    Hint(sync.lastSyncedLabel, Modifier.testTag(SYNC_LAST_SYNCED_TAG))
    sync.statusDetail?.let { StatusDetail(it, onRetry = actions.onSyncNowClicked, retryEnabled = !sync.syncing) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = actions.onSyncNowClicked, enabled = !sync.syncing, modifier = Modifier.testTag(SYNC_NOW_TAG)) {
            Icon(MuvissIcons.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(if (sync.syncing) " Syncing…" else " Sync now")
        }
        TextButton(onClick = actions.onSignOutClicked, enabled = !sync.syncing, modifier = Modifier.testTag(SYNC_SIGN_OUT_TAG)) { Text("Sign out") }
    }
}

@Composable
private fun StatusDetail(detail: SyncStatusDetail, onRetry: () -> Unit, retryEnabled: Boolean) {
    val failed = detail is SyncStatusDetail.Failed
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            SyncCopy.detail(detail),
            style = MaterialTheme.typography.bodySmall,
            color = if (failed || detail == SyncStatusDetail.AccountChanged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false).testTag(SYNC_DETAIL_TAG),
        )
        if (failed) {
            TextButton(onClick = onRetry, enabled = retryEnabled, modifier = Modifier.testTag(SYNC_RETRY_TAG)) { Text("Retry") }
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
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(SYNC_AUTOMATIC_SWITCH_TAG)
            .toggleable(
                value = sync.automaticSync,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = actions.onAutomaticSyncToggled,
            )
            .semantics {
                contentDescription = AUTOMATIC_SYNC_LABEL
                stateDescription = if (sync.automaticSync) "On" else "Off"
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(AUTOMATIC_SYNC_LABEL, style = MaterialTheme.typography.bodyMedium)
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
    ) { Text("Resync everything") }
}

@Composable
private fun ResyncDialog(actions: SyncSectionActions) {
    AlertDialog(
        onDismissRequest = actions.onResyncEverythingDismissed,
        modifier = Modifier.testTag(SYNC_RESYNC_DIALOG_TAG),
        title = { Text("Resync everything?") },
        text = {
            Text(
                "This sends your whole library to your account again and downloads everything in it. " +
                    "It can take a while and use data. Nothing is deleted.",
            )
        },
        confirmButton = {
            TextButton(onClick = actions.onResyncEverythingConfirmed, modifier = Modifier.testTag(SYNC_RESYNC_CONFIRM_TAG)) { Text("Resync") }
        },
        dismissButton = {
            TextButton(onClick = actions.onResyncEverythingDismissed, modifier = Modifier.testTag(SYNC_RESYNC_CANCEL_TAG)) { Text("Cancel") }
        },
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 4.dp),
    )
}
