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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.common.crash.CrashReporter
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.common.notifications.SystemNotificationSettings
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.text.label
import com.codingpit.muviss.core.designsystem.text.resolve
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.SupportedLocales
import com.codingpit.muviss.feature.settings.ui.generated.resources.Res
import com.codingpit.muviss.feature.settings.ui.generated.resources.action_close
import com.codingpit.muviss.feature.settings.ui.generated.resources.animations
import com.codingpit.muviss.feature.settings.ui.generated.resources.animations_body
import com.codingpit.muviss.feature.settings.ui.generated.resources.app_version
import com.codingpit.muviss.feature.settings.ui.generated.resources.crash_reports
import com.codingpit.muviss.feature.settings.ui.generated.resources.crash_reports_body
import com.codingpit.muviss.feature.settings.ui.generated.resources.export
import com.codingpit.muviss.feature.settings.ui.generated.resources.fill_library
import com.codingpit.muviss.feature.settings.ui.generated.resources.fill_library_value
import com.codingpit.muviss.feature.settings.ui.generated.resources.import
import com.codingpit.muviss.feature.settings.ui.generated.resources.licenses
import com.codingpit.muviss.feature.settings.ui.generated.resources.notifications
import com.codingpit.muviss.feature.settings.ui.generated.resources.notifications_blocked
import com.codingpit.muviss.feature.settings.ui.generated.resources.open_settings
import com.codingpit.muviss.feature.settings.ui.generated.resources.provider_region
import com.codingpit.muviss.feature.settings.ui.generated.resources.scheme_four_way
import com.codingpit.muviss.feature.settings.ui.generated.resources.scheme_three_way
import com.codingpit.muviss.feature.settings.ui.generated.resources.section_about
import com.codingpit.muviss.feature.settings.ui.generated.resources.section_appearance
import com.codingpit.muviss.feature.settings.ui.generated.resources.section_content
import com.codingpit.muviss.feature.settings.ui.generated.resources.section_data
import com.codingpit.muviss.feature.settings.ui.generated.resources.section_privacy
import com.codingpit.muviss.feature.settings.ui.generated.resources.section_triage
import com.codingpit.muviss.feature.settings.ui.generated.resources.settings_title
import com.codingpit.muviss.feature.settings.ui.generated.resources.snooze_period
import com.codingpit.muviss.feature.settings.ui.generated.resources.snooze_placement
import com.codingpit.muviss.feature.settings.ui.generated.resources.swipe_animations
import com.codingpit.muviss.feature.settings.ui.generated.resources.swipe_animations_body
import com.codingpit.muviss.feature.settings.ui.generated.resources.swipe_controls
import com.codingpit.muviss.feature.settings.ui.generated.resources.system_default
import com.codingpit.muviss.feature.settings.ui.generated.resources.theme
import com.codingpit.muviss.feature.settings.ui.generated.resources.theme_dark
import com.codingpit.muviss.feature.settings.ui.generated.resources.theme_light
import com.codingpit.muviss.feature.settings.ui.generated.resources.theme_system
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_attribution
import com.codingpit.muviss.feature.settings.ui.generated.resources.tmdb_language
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenLicenses: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenTriage: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exporter = rememberDataExporter()
    val systemDefault = stringResource(Res.string.system_default)

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

    // Settings that failed to load are defaults, not the user's choices; a
    // switch drawn from them would lie, and flipping it would overwrite the
    // real value. So the error replaces the screen (#73).
    state.error?.let { message ->
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            ErrorState(message.resolve(), onRetry = viewModel::retry)
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
        Text(stringResource(Res.string.settings_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = MuvissSpacing.s))

        SectionOverline(stringResource(Res.string.section_appearance))
        ThemeRow(state.settings.theme, viewModel::onThemeSelected)
        SwitchRow(
            label = stringResource(Res.string.notifications),
            description = stringResource(notificationsSupportNote),
            checked = state.settings.notificationsEnabled,
            onToggle = viewModel::onNotificationsToggled,
        )
        NotificationsBlockedNote(state.settings.notificationsEnabled)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        SwitchRow(
            label = stringResource(Res.string.animations),
            description = stringResource(Res.string.animations_body),
            checked = state.animationsEnabled,
            onToggle = viewModel::onAnimationsToggled,
        )

        SectionOverline(stringResource(Res.string.section_content), topPadding = true)
        PickerRow(
            label = stringResource(Res.string.tmdb_language),
            value = SupportedLocales.languages.firstOrNull { it.code == state.settings.language }?.displayName
                ?: systemDefault,
            options = listOf(SupportedLocales.SYSTEM_DEFAULT_LANGUAGE to systemDefault) +
                SupportedLocales.languages.map { it.code to it.displayName },
            selectedCode = state.settings.language,
            onSelect = viewModel::onLanguageSelected,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        PickerRow(
            label = stringResource(Res.string.provider_region),
            value = SupportedLocales.regions.firstOrNull { it.code == state.settings.region }?.displayName ?: state.settings.region,
            options = SupportedLocales.regions.map { it.code to it.displayName },
            selectedCode = state.settings.region,
            onSelect = viewModel::onRegionSelected,
        )

        SectionOverline(stringResource(Res.string.section_triage), topPadding = true)
        ActionRow(
            icon = MuvissIcons.CaughtUp,
            label = stringResource(Res.string.fill_library),
            value = stringResource(Res.string.fill_library_value),
            onClick = onOpenTriage,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        PickerRow(
            label = stringResource(Res.string.swipe_controls),
            value = state.triageControlScheme.displayName(),
            options = TriageControlScheme.entries.map { it.name to it.displayName() },
            selectedCode = state.triageControlScheme.name,
            onSelect = { name -> viewModel.onTriageControlSchemeSelected(TriageControlScheme.fromStored(name)) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        // No on/off switch above these two: snoozing is reached from a button
        // on the card, so there is nothing to hide. What a person actually
        // wants to change is how long it waits and where it comes back.
        PickerRow(
            label = stringResource(Res.string.snooze_period),
            value = state.snoozePeriod.label(),
            options = SnoozePeriod.entries.map { it.name to it.label() },
            selectedCode = state.snoozePeriod.name,
            onSelect = { name -> viewModel.onSnoozePeriodSelected(SnoozePeriod.fromStored(name)) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        PickerRow(
            label = stringResource(Res.string.snooze_placement),
            value = state.snoozePlacement.label(),
            options = SnoozePlacement.entries.map { it.name to it.label() },
            selectedCode = state.snoozePlacement.name,
            onSelect = { name -> viewModel.onSnoozePlacementSelected(SnoozePlacement.fromStored(name)) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        SwitchRow(
            label = stringResource(Res.string.swipe_animations),
            description = stringResource(Res.string.swipe_animations_body),
            checked = state.triageDeckAnimations,
            // Greyed rather than hidden while the master switch is off: the
            // stored position stays visible, so turning motion back on returns
            // the deck to whatever the user last chose here.
            enabled = state.animationsEnabled,
            onToggle = viewModel::onTriageDeckAnimationsToggled,
        )

        SectionOverline(stringResource(Res.string.section_data), topPadding = true)
        ActionRow(
            icon = MuvissIcons.Import,
            label = stringResource(Res.string.import),
            value = "Trakt · TV Time · CSV",
            onClick = onOpenImport,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ActionRow(
            icon = MuvissIcons.Export,
            label = stringResource(Res.string.export),
            value = "JSON",
            onClick = viewModel::exportData,
        )
        state.exportError?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        // Every platform has a reporter now (#83), so this always renders in
        // practice — CrashReporter.isAvailable stays the gate on principle,
        // in case a future CrashBackend legitimately has none.
        if (CrashReporter.isAvailable) {
            SectionOverline(stringResource(Res.string.section_privacy), topPadding = true)
            SwitchRow(
                label = stringResource(Res.string.crash_reports),
                description = stringResource(Res.string.crash_reports_body),
                checked = state.settings.crashReportsEnabled,
                onToggle = viewModel::onCrashReportsToggled,
            )
        }

        SectionOverline(stringResource(Res.string.section_about), topPadding = true)
        AboutSection(
            appVersionName = state.appVersion.versionName,
            onOpenLicenses = onOpenLicenses,
            onTestCrash = { throw MuvissTestCrash() },
        )
    }
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
        Text(stringResource(Res.string.theme), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
            AppTheme.entries.forEach { theme ->
                FilterChip(
                    selected = theme == selected,
                    onClick = { onSelect(theme) },
                    label = { Text(stringResource(theme.label())) },
                )
            }
        }
    }
}

private fun AppTheme.label(): StringResource = when (this) {
    AppTheme.LIGHT -> Res.string.theme_light
    AppTheme.DARK -> Res.string.theme_dark
    AppTheme.SYSTEM -> Res.string.theme_system
}

/**
 * Notifications that the OS will not show (EPIC 30, #73). On Android 13+ a
 * denied permission is sticky, so the switch above would store a preference
 * that does nothing; this says so and opens the one place it can be fixed.
 * Re-checked on resume, because that is how the user comes back from there.
 */
@Composable
private fun NotificationsBlockedNote(enabledInApp: Boolean) {
    val system = LocalSystemNotificationSettings.current
    var blocked by remember { mutableStateOf(system.blocked()) }
    LifecycleResumeEffect(system) {
        blocked = system.blocked()
        onPauseOrDispose { }
    }
    if (!enabledInApp || !blocked) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testTag(NOTIFICATIONS_BLOCKED_TAG),
    ) {
        Text(
            stringResource(Res.string.notifications_blocked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = system::open) { Text(stringResource(Res.string.open_settings)) }
    }
}

/** The platform's notification state; Android provides a real one from `MuvissApp` (EPIC 30, #73). */
val LocalSystemNotificationSettings = staticCompositionLocalOf<SystemNotificationSettings> { SystemNotificationSettings.None }

internal const val NOTIFICATIONS_BLOCKED_TAG = "settings-notifications-blocked"

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
                TextButton(onClick = { open = false }) { Text(stringResource(Res.string.action_close)) }
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

/**
 * Tapping the version [TEST_CRASH_TAPS] times reveals [TEST_CRASH_LABEL], which
 * hands control to [onTestCrash] — on the real screen an uncaught throw.
 *
 * It ships in release builds on purpose: verifying crash reporting means a crash
 * from the exact artefact the store serves, because that is what proves the R8
 * mapping uploaded with it matches (`docs/RELEASING.md`, "Sentry test crash").
 * The crash takes the ordinary path, so the "Send crash reports" switch governs
 * it like any other, which makes it the opt-out check too.
 */
@Composable
internal fun AboutSection(appVersionName: String, onOpenLicenses: () -> Unit, onTestCrash: () -> Unit) {
    var versionTaps by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
        Text(
            stringResource(Res.string.app_version, appVersionName),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(VERSION_ROW_TAG).clickable { versionTaps++ },
        )
        if (versionTaps >= TEST_CRASH_TAPS) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onTestCrash).padding(vertical = MuvissSpacing.s),
            ) {
                Text(TEST_CRASH_LABEL, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            }
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(Res.string.tmdb_attribution),
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
            Text(stringResource(Res.string.licenses), style = MaterialTheme.typography.bodyLarge)
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
@Composable
private fun TriageControlScheme.displayName(): String = stringResource(
    when (this) {
        TriageControlScheme.FOUR_WAY -> Res.string.scheme_four_way
        TriageControlScheme.THREE_WAY -> Res.string.scheme_three_way
    },
)

internal const val VERSION_ROW_TAG = "settings_version_row"

internal const val TEST_CRASH_TAPS = 7

/** Not localized: an operator's tool, reached only by the hidden gesture. */
internal const val TEST_CRASH_LABEL = "Send test crash"

/** What the hidden "Send test crash" action throws; the name is what to search for in Sentry. */
internal class MuvissTestCrash : RuntimeException("MuvissTestCrash: triggered from Settings > About")

/** Material3's own disabled-content opacity, which `Switch` applies to itself. */
private const val DISABLED_ALPHA = 0.38f
