package com.codingpit.muviss.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.common.crash.CrashReporter
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.SupportedLocales
import com.codingpit.muviss.feature.settings.domain.TMDB_ATTRIBUTION_TEXT

internal const val CRASH_REPORTS_LABEL = "Send crash reports"

/**
 * Says what `docs/PRIVACY.md` says, in the words a person deciding this needs:
 * what is sent, and that the library is not part of it.
 */
internal const val CRASH_REPORTS_DESCRIPTION = "Anonymous. Sent only when something breaks, and never includes your library or watch history"

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenLicenses: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenTriage: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exporter = rememberDataExporter()

    LaunchedEffect(state.exportJson) {
        val json = state.exportJson ?: return@LaunchedEffect
        exporter.export(json, "muviss-export.json")
        viewModel.exportHandled()
    }

    if (state.loading) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.padding(top = MuvissSpacing.xxl))
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(MuvissSpacing.l)
            .padding(bottom = MuvissSpacing.bottomContent),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = MuvissSpacing.s))

        state.error?.let { ErrorBanner(it) }

        SectionOverline("Appearance")
        ThemeRow(state.settings.theme, viewModel::onThemeSelected)
        SwitchRow(
            label = "Notifications",
            description = notificationsSupportNote,
            checked = state.settings.notificationsEnabled,
            onToggle = viewModel::onNotificationsToggled,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        SwitchRow(
            label = "Animations",
            description = "Motion and transitions across the app",
            checked = state.animationsEnabled,
            onToggle = viewModel::onAnimationsToggled,
        )

        SectionOverline("Content", topPadding = true)
        PickerRow(
            label = "TMDB language",
            value = SupportedLocales.languages.firstOrNull { it.code == state.settings.language }?.displayName ?: state.settings.language,
            options = SupportedLocales.languages.map { it.code to it.displayName },
            selectedCode = state.settings.language,
            onSelect = viewModel::onLanguageSelected,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        PickerRow(
            label = "Watch-provider region",
            value = SupportedLocales.regions.firstOrNull { it.code == state.settings.region }?.displayName ?: state.settings.region,
            options = SupportedLocales.regions.map { it.code to it.displayName },
            selectedCode = state.settings.region,
            onSelect = viewModel::onRegionSelected,
        )

        SectionOverline("Triage", topPadding = true)
        ActionRow(
            icon = MuvissIcons.CaughtUp,
            label = "Fill your library",
            value = "Sort titles quickly",
            onClick = onOpenTriage,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        PickerRow(
            label = "Swipe controls",
            value = state.triageControlScheme.displayName,
            options = TriageControlScheme.entries.map { it.name to it.displayName },
            selectedCode = state.triageControlScheme.name,
            onSelect = { name -> viewModel.onTriageControlSchemeSelected(TriageControlScheme.fromStored(name)) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        // No on/off switch above these two: snoozing is reached from a button
        // on the card, so there is nothing to hide. What a person actually
        // wants to change is how long it waits and where it comes back.
        PickerRow(
            label = SNOOZE_PERIOD_LABEL,
            value = state.snoozePeriod.label,
            options = SnoozePeriod.entries.map { it.name to it.label },
            selectedCode = state.snoozePeriod.name,
            onSelect = { name -> viewModel.onSnoozePeriodSelected(SnoozePeriod.fromStored(name)) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        PickerRow(
            label = SNOOZE_PLACEMENT_LABEL,
            value = state.snoozePlacement.label,
            options = SnoozePlacement.entries.map { it.name to it.label },
            selectedCode = state.snoozePlacement.name,
            onSelect = { name -> viewModel.onSnoozePlacementSelected(SnoozePlacement.fromStored(name)) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        SwitchRow(
            label = "Swipe animations",
            description = "Cards fly out when decided, and undo brings them back",
            checked = state.triageDeckAnimations,
            // Greyed rather than hidden while the master switch is off: the
            // stored position stays visible, so turning motion back on returns
            // the deck to whatever the user last chose here.
            enabled = state.animationsEnabled,
            onToggle = viewModel::onTriageDeckAnimationsToggled,
        )

        SectionOverline("Data", topPadding = true)
        ActionRow(
            icon = MuvissIcons.Import,
            label = "Import",
            value = "Trakt · TV Time · CSV",
            onClick = onOpenImport,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ActionRow(
            icon = MuvissIcons.Export,
            label = "Export",
            value = "JSON",
            onClick = viewModel::exportData,
        )
        state.exportError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        // Web has no reporter (docs/PRIVACY.md), so a switch there would be a lie.
        if (CrashReporter.isAvailable) {
            SectionOverline("Privacy", topPadding = true)
            SwitchRow(
                label = CRASH_REPORTS_LABEL,
                description = CRASH_REPORTS_DESCRIPTION,
                checked = state.settings.crashReportsEnabled,
                onToggle = viewModel::onCrashReportsToggled,
            )
        }

        SectionOverline("About", topPadding = true)
        AboutSection(appVersionName = state.appVersion.versionName, onOpenLicenses = onOpenLicenses)
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

/** Uppercase overline section header, per the design doc's grouped settings list. */
@Composable
private fun SectionOverline(text: String, topPadding: Boolean = false) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = if (topPadding) MuvissSpacing.l else 0.dp, bottom = MuvissSpacing.xs),
    )
}

@Composable
private fun ThemeRow(selected: AppTheme, onSelect: (AppTheme) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Theme", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
            AppTheme.entries.forEach { theme ->
                FilterChip(
                    selected = theme == selected,
                    onClick = { onSelect(theme) },
                    label = { Text(theme.label()) },
                )
            }
        }
    }
}

private fun AppTheme.label(): String = when (this) {
    AppTheme.LIGHT -> "Light"
    AppTheme.DARK -> "Dark"
    AppTheme.SYSTEM -> "System"
}

/**
 * "Label / description — switch" row.
 *
 * [enabled] is what a dependent toggle uses: a row governed by a master switch
 * stays readable at its stored position and simply stops responding, which is
 * why the whole row dims rather than disappearing. The label is dimmed along
 * with the control so the row does not read as active text beside a dead
 * switch.
 */
@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        Modifier.fillMaxWidth().padding(vertical = MuvissSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = MuvissSpacing.s)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
    }
}

/** "Label — value ›" row opening a radio-list dialog; replaces the old chip rows. */
@Composable
private fun PickerRow(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    selectedCode: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = MuvissSpacing.m),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(
                MuvissIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { (code, displayName) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = code == selectedCode, onClick = {
                                    onSelect(code)
                                    open = false
                                })
                                .padding(vertical = MuvissSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = code == selectedCode, onClick = null)
                            Text(displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = MuvissSpacing.s))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Close") }
            },
        )
    }
}

/** Icon + label row with an amber value hint, for Import/Export style actions. */
@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = MuvissSpacing.m),
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Icon(
            MuvissIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun AboutSection(appVersionName: String, onOpenLicenses: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
        Text("Muviss $appVersionName", style = MaterialTheme.typography.bodyMedium)
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                TMDB_ATTRIBUTION_TEXT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MuvissSpacing.m),
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenLicenses).padding(vertical = MuvissSpacing.s),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Open source licenses", style = MaterialTheme.typography.bodyLarge)
            Icon(
                MuvissIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Both schemes keep left/right/up on the three high-frequency verdicts; they
 * differ only in whether a downward drag commits Watching or is inert.
 */
private val TriageControlScheme.displayName: String
    get() = when (this) {
        TriageControlScheme.FOUR_WAY -> "Four directions"
        TriageControlScheme.THREE_WAY -> "Three directions + button"
    }

internal const val SNOOZE_PERIOD_LABEL = "Ask me again after"

internal const val SNOOZE_PLACEMENT_LABEL = "Snoozed titles come back"

/** Material3's own disabled-content opacity, which `Switch` applies to itself. */
private const val DISABLED_ALPHA = 0.38f
