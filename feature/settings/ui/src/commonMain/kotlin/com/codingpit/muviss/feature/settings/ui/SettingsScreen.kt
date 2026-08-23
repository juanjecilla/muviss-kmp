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
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.SupportedLocales
import com.codingpit.muviss.feature.settings.domain.TMDB_ATTRIBUTION_TEXT

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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MuvissSpacing.l),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = MuvissSpacing.s))

        state.error?.let { ErrorBanner(it) }

        SectionOverline("Appearance")
        ThemeRow(state.settings.theme, viewModel::onThemeSelected)
        NotificationsRow(state.settings.notificationsEnabled, viewModel::onNotificationsToggled)

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

@Composable
private fun NotificationsRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = MuvissSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Notifications", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Reminders for new episodes (coming soon)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
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
