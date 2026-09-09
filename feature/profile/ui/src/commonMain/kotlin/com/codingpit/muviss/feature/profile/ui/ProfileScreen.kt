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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
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
import com.codingpit.muviss.feature.profile.domain.RewatchEntry
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import com.codingpit.muviss.feature.profile.domain.SyncProvider
import kotlinx.coroutines.launch
import kotlin.math.round

@Composable
fun ProfileScreen(viewModel: ProfileViewModel, onOpenRewatch: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    // Purely presentational — whether a sheet is open is not something the
    // ViewModel or the domain has any use for.
    val paywall = rememberPaywallPresenter()
    var paywallVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.sync.message) {
        val message = state.sync.message ?: return@LaunchedEffect
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
        viewModel.syncMessageShown()
    }

    // Sign-in leaves the app: the provider's page runs in a browser and comes
    // back as a redirect into MainActivity, not as a result here. Consuming
    // the URL immediately keeps returning to this screen from reopening it.
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(state.sync.pendingAuthUrl) {
        val url = state.sync.pendingAuthUrl ?: return@LaunchedEffect
        uriHandler.openUri(url)
        viewModel.authUrlOpened()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        if (state.loading) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.padding(top = MuvissSpacing.xxl))
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(MuvissSpacing.l)
                .padding(bottom = MuvissSpacing.bottomContent),
            verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xl),
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
                onUnlockClicked = { paywallVisible = true },
            )
            HorizontalDivider()

            if (state.stats.isEmpty) {
                EmptyLibraryState()
            } else {
                StatsSection(state.stats, onOpenRewatch)
            }
        }

        paywall.Paywall(visible = paywallVisible, onDismiss = { paywallVisible = false })

        if (state.isEditingName) {
            EditNameDialog(
                currentName = state.profile.displayName,
                onConfirm = viewModel::onDisplayNameConfirmed,
                onDismiss = viewModel::onEditNameDismissed,
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
 *
 * [SyncAccountState.Locked] is the deliberate opposite (ADR 0018): the build
 * has sync, the user has not bought it, so the row appears and offers the
 * purchase. Hiding it would leave a paid feature undiscoverable.
 */
@Composable
private fun SyncSection(
    sync: SyncUiState,
    onSignInClicked: (SyncProvider) -> Unit,
    onSyncNowClicked: () -> Unit,
    onSignOutClicked: () -> Unit,
    onUnlockClicked: () -> Unit,
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

                is SyncAccountState.Locked -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sync across devices", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Keep your library, progress and rewatch history on every device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onUnlockClicked) { Text("Unlock sync") }
                        // A lapsed subscriber is still signed in; without this
                        // the paywall would be the only thing they can reach.
                        if (account.email != null) {
                            TextButton(onClick = onSignOutClicked, enabled = !sync.syncing) { Text("Sign out") }
                        }
                    }
                }

                SyncAccountState.SignedOut -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Account", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Everything stays on this device today.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    // One button per provider rather than a picker: there are
                    // two at most, and a picker would add a step to the one
                    // action on this row.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sync.providers.forEach { provider ->
                            OutlinedButton(onClick = { onSignInClicked(provider) }, enabled = !sync.syncing) {
                                Text("Sign in with ${provider.displayName}")
                            }
                        }
                    }
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
private fun EmptyLibraryState() {
    EmptyState(
        icon = MuvissIcons.Profile,
        title = "No stats yet",
        body = "Save titles to your library and tick episodes to see your stats here.",
    )
}

/**
 * The stats block: tiles, the status bars, the genre donut and the rewatch
 * card.
 *
 * `internal` rather than private so a screenshot test can render it directly —
 * the alignment of the donut against the screen's gutter is the kind of thing
 * only a picture of the whole section shows (same reason [MostRewatchedCard]
 * is internal).
 *
 * Spacing inside the section is one step below the gap *between* sections, so
 * the blocks in here read as belonging together.
 */
@Composable
internal fun StatsSection(
    stats: ProfileStats,
    onOpenRewatch: () -> Unit,
    wide: Boolean = rememberWideChartLayout(),
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.l)) {
        Text("Stats", style = MaterialTheme.typography.titleSmall)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m)) {
            StatTile(stats.moviesWatched.toString(), "movies watched", Modifier.weight(1f))
            StatTile(stats.episodesSeen.toString(), "episodes seen", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m)) {
            StatTile(formatHours(stats.estimatedHoursWatched), "hours watched", Modifier.weight(1f))
            StatTile("${stats.streak.currentDays}d", "streak (best ${stats.streak.longestDays}d)", Modifier.weight(1f))
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
            Text("By status", style = MaterialTheme.typography.labelLarge)
            StatusBarChart(stats.statusBreakdown, Modifier.fillMaxWidth())
        }

        if (stats.genreBreakdown.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                Text("By genre", style = MaterialTheme.typography.labelLarge)
                GenreDonutChart(foldGenresIntoOther(stats.genreBreakdown), wide = wide)
            }
        }

        MostRewatchedCard(stats.mostRewatched, onOpenRewatch)
    }
}

/**
 * Top rewatched titles, and the way into the full ranking.
 *
 * It renders even with nothing in it, which is deliberate: the backfill that
 * shipped with the rewatch history gave every already-seen episode exactly one
 * viewing (ADR 0011), so every install's first look at this card is the empty
 * one — and the "Watched again" gesture that fills it is advertised nowhere
 * else in the app.
 *
 * Shows and films sit in one list here, which the full screen does not allow
 * itself; the per-row unit is what makes the mix readable (ADR 0012).
 */
@Composable
internal fun MostRewatchedCard(entries: List<RewatchEntry>, onOpenRewatch: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().testTag(MOST_REWATCHED_CARD_TAG),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = entries.isNotEmpty(), onClick = onOpenRewatch),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Most rewatched", style = MaterialTheme.typography.labelLarge)
            if (entries.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("See all", style = MaterialTheme.typography.labelMedium)
                    Icon(MuvissIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (entries.isEmpty()) {
            Text(
                "Nothing rewatched yet. $REWATCH_HOW_TO",
                modifier = Modifier.testTag(MOST_REWATCHED_EMPTY_TAG),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entries.forEach { entry ->
                Row(
                    Modifier.fillMaxWidth().testTag(MOST_REWATCHED_ROW_TAG),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        entry.rewatchLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

const val MOST_REWATCHED_CARD_TAG = "most_rewatched_card"
const val MOST_REWATCHED_ROW_TAG = "most_rewatched_row"
const val MOST_REWATCHED_EMPTY_TAG = "most_rewatched_empty"

private const val ONE_DECIMAL = 10.0

private fun formatHours(hours: Double): String {
    val roundedToOneDecimal = round(hours * ONE_DECIMAL) / ONE_DECIMAL
    return "${roundedToOneDecimal}h"
}
