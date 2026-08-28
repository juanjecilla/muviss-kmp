package com.codingpit.muviss.feature.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.StatTile
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.profile.domain.AvatarPreset
import com.codingpit.muviss.feature.profile.domain.AvatarPresets
import com.codingpit.muviss.feature.profile.domain.LocalProfile
import com.codingpit.muviss.feature.profile.domain.ProfileStats
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import kotlinx.coroutines.launch
import kotlin.math.round

@Composable
fun ProfileScreen(viewModel: ProfileViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(state.sync.message) {
        val message = state.sync.message ?: return@LaunchedEffect
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
        viewModel.syncMessageShown()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        if (state.loading) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.padding(top = 32.dp))
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = MuvissSpacing.bottomContent),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            IdentitySection(
                profile = state.profile,
                onEditName = viewModel::onEditNameRequested,
                onAvatarSelected = viewModel::onAvatarSelected,
            )
            HorizontalDivider()
            SyncSection(
                sync = state.sync,
                onSignInClicked = viewModel::onSignInClicked,
                onSyncNowClicked = viewModel::onSyncNowClicked,
                onSignOutClicked = viewModel::onSignOutClicked,
            )
            HorizontalDivider()

            if (state.stats.isEmpty) {
                EmptyLibraryState()
            } else {
                StatsSection(state.stats)
            }
        }

        if (state.isEditingName) {
            EditNameDialog(
                currentName = state.profile.displayName,
                onConfirm = viewModel::onDisplayNameConfirmed,
                onDismiss = viewModel::onEditNameDismissed,
            )
        }

        if (state.sync.isEnteringEmail) {
            SignInEmailDialog(
                onConfirm = viewModel::onSignInEmailConfirmed,
                onDismiss = viewModel::onSignInEmailDismissed,
            )
        }

        if (state.sync.isEnteringCode) {
            SignInCodeDialog(
                email = state.sync.pendingEmail.orEmpty(),
                onConfirm = viewModel::onSignInCodeConfirmed,
                onDismiss = viewModel::onSignInCodeDismissed,
            )
        }
    }
}

@Composable
private fun IdentitySection(
    profile: LocalProfile,
    onEditName: () -> Unit,
    onAvatarSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AvatarBadge(profile.avatar, profile.displayName, size = 64.dp)
            Column {
                Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onEditName, contentPadding = PaddingValues(0.dp)) {
                    Text("Edit name")
                }
            }
        }
        Text("Avatar", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(AvatarPresets.all, key = { it.id }) { preset ->
                AvatarBadge(
                    preset = preset,
                    displayName = profile.displayName,
                    size = 44.dp,
                    selected = preset.id == profile.avatarId,
                    onClick = { onAvatarSelected(preset.id) },
                )
            }
        }
    }
}

@Composable
private fun AvatarBadge(
    preset: AvatarPreset,
    displayName: String,
    size: Dp,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val color = Color(preset.colorArgb)
    val initial = displayName.trim().take(1).uppercase().ifEmpty { "?" }
    var modifier = Modifier.size(size).background(color, CircleShape)
    if (selected) modifier = modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
    if (onClick != null) modifier = modifier.clickable(onClick = onClick)

    Box(modifier, contentAlignment = Alignment.Center) {
        Text(initial, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EditNameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Account/sync section (EPIC 9). Renders nothing when sync isn't configured
 * for this build ([SyncAccountState.Unavailable]) — the entry point is
 * hidden entirely rather than shown disabled, same contract as a blank
 * Sentry DSN (CLAUDE.md).
 */
@Composable
private fun SyncSection(
    sync: SyncUiState,
    onSignInClicked: () -> Unit,
    onSyncNowClicked: () -> Unit,
    onSignOutClicked: () -> Unit,
) {
    val account = sync.account
    if (account == SyncAccountState.Unavailable) return

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MuvissIcons.Account,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            when (account) {
                SyncAccountState.Unavailable -> Unit

                SyncAccountState.SignedOut -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Account", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Everything stays on this device today.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    OutlinedButton(onClick = onSignInClicked, enabled = !sync.syncing) { Text("Sign in to sync") }
                }

                is SyncAccountState.SignedIn -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(account.email ?: "Signed in", style = MaterialTheme.typography.titleSmall)
                    Text(
                        sync.lastSyncedLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onSyncNowClicked, enabled = !sync.syncing) {
                            Icon(MuvissIcons.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(if (sync.syncing) " Syncing…" else " Sync now")
                        }
                        TextButton(onClick = onSignOutClicked, enabled = !sync.syncing) { Text("Sign out") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignInEmailDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var email by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in to sync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("We'll email you a one-time code — no password needed.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = email, onValueChange = { email = it }, singleLine = true, label = { Text("Email") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(email) }, enabled = email.isNotBlank()) { Text("Send code") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SignInCodeDialog(email: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter the code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("We sent a code to $email.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = code, onValueChange = { code = it }, singleLine = true, label = { Text("Code") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(code) }, enabled = code.isNotBlank()) { Text("Verify") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EmptyLibraryState() {
    EmptyState(
        icon = MuvissIcons.Profile,
        title = "No stats yet",
        body = "Save titles to your library and tick episodes to see your stats here.",
    )
}

@Composable
private fun StatsSection(stats: ProfileStats) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Stats", style = MaterialTheme.typography.titleSmall)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(stats.moviesWatched.toString(), "movies watched", Modifier.weight(1f))
            StatTile(stats.episodesSeen.toString(), "episodes seen", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(formatHours(stats.estimatedHoursWatched), "hours watched", Modifier.weight(1f))
            StatTile("${stats.streak.currentDays}d", "streak (best ${stats.streak.longestDays}d)", Modifier.weight(1f))
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("By status", style = MaterialTheme.typography.labelLarge)
            StatusBarChart(stats.statusBreakdown, Modifier.fillMaxWidth())
        }

        if (stats.genreBreakdown.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("By genre", style = MaterialTheme.typography.labelLarge)
                GenreDonutChart(foldGenresIntoOther(stats.genreBreakdown))
            }
        }
    }
}

private const val ONE_DECIMAL = 10.0

private fun formatHours(hours: Double): String {
    val roundedToOneDecimal = round(hours * ONE_DECIMAL) / ONE_DECIMAL
    return "${roundedToOneDecimal}h"
}
