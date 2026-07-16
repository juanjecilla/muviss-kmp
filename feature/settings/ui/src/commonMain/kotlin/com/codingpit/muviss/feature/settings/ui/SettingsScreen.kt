package com.codingpit.muviss.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.SupportedLocales
import com.codingpit.muviss.feature.settings.domain.TMDB_ATTRIBUTION_TEXT

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenLicenses: () -> Unit,
    onOpenImport: () -> Unit,
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
            CircularProgressIndicator(Modifier.padding(top = 32.dp))
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        state.error?.let { ErrorBanner(it) }

        ThemeSection(state.settings.theme, viewModel::onThemeSelected)
        HorizontalDivider()
        LanguageSection(state.settings.language, viewModel::onLanguageSelected)
        HorizontalDivider()
        RegionSection(state.settings.region, viewModel::onRegionSelected)
        HorizontalDivider()
        NotificationsSection(state.settings.notificationsEnabled, viewModel::onNotificationsToggled)
        HorizontalDivider()
        ExportSection(onExport = viewModel::exportData, error = state.exportError)
        HorizontalDivider()
        ImportSection(onOpenImport = onOpenImport)
        HorizontalDivider()
        AboutSection(appVersionName = state.appVersion.versionName, onOpenLicenses = onOpenLicenses)
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun ThemeSection(selected: AppTheme, onSelect: (AppTheme) -> Unit) {
    Column {
        SectionTitle("Theme")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun LanguageSection(selected: String, onSelect: (String) -> Unit) {
    Column {
        SectionTitle("TMDB language")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SupportedLocales.languages, key = { it.code }) { language ->
                FilterChip(
                    selected = language.code == selected,
                    onClick = { onSelect(language.code) },
                    label = { Text(language.displayName) },
                )
            }
        }
    }
}

@Composable
private fun RegionSection(selected: String, onSelect: (String) -> Unit) {
    Column {
        SectionTitle("Watch-provider region")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SupportedLocales.regions, key = { it.code }) { region ->
                FilterChip(
                    selected = region.code == selected,
                    onClick = { onSelect(region.code) },
                    label = { Text(region.displayName) },
                )
            }
        }
    }
}

@Composable
private fun NotificationsSection(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            SectionTitle("Notifications")
            Text("Reminders for new episodes (coming soon)", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun ExportSection(onExport: () -> Unit, error: String?) {
    Column {
        SectionTitle("Export your data")
        Text(
            "Save your library and watch progress as a JSON file.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Button(onClick = onExport) { Text("Export data") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ImportSection(onOpenImport: () -> Unit) {
    Column {
        SectionTitle("Import from another tracker")
        Text(
            "Bring in your library and watch history from Trakt, TV Time, or a CSV file.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Button(onClick = onOpenImport) { Text("Import data") }
    }
}

@Composable
private fun AboutSection(appVersionName: String, onOpenLicenses: () -> Unit) {
    Column {
        SectionTitle("About")
        Text("Muviss $appVersionName", style = MaterialTheme.typography.bodyMedium)
        Text(
            TMDB_ATTRIBUTION_TEXT,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        TextButton(onClick = onOpenLicenses) { Text("Open source licenses") }
    }
}
