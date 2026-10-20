package com.codingpit.muviss.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.common.notifications.platformHasHomeWidgets
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.component.TmdbKeyDialog
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.Res
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_back
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_backup_body
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_backup_switch
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_backup_title
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_discover_body
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_discover_title
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_done_body
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_done_title
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_import_action
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_import_body
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_import_title
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_key_body
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_key_change
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_key_muviss
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_key_title
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_key_use_own
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_key_yours
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_library_body
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_library_title
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_muted_body
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_muted_more
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_muted_news
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_muted_news_examples
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_muted_reality
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_muted_reality_examples
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_muted_soap
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_muted_soap_examples
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_muted_talk
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_muted_talk_examples
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_muted_title
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_next
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_notifications_blocked
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_notifications_body
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_notifications_open_settings
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_notifications_switch
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_notifications_title
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_skip
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_start
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_watch_next_body
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_watch_next_title
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_watch_next_widget
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_welcome_body
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_welcome_title
import com.codingpit.muviss.feature.settings.api.TmdbDataSource
import org.jetbrains.compose.resources.stringResource

const val ONBOARDING_SKIP_TAG = "onboardingSkip"
const val ONBOARDING_NEXT_TAG = "onboardingNext"
const val ONBOARDING_BACK_TAG = "onboardingBack"
const val ONBOARDING_START_TAG = "onboardingStart"
const val ONBOARDING_NOTIFICATIONS_SWITCH_TAG = "onboardingNotificationsSwitch"
const val ONBOARDING_DEVICE_BACKUP_SWITCH_TAG = "onboardingDeviceBackupSwitch"
const val ONBOARDING_KEY_BUTTON_TAG = "onboardingKeyButton"

/** Test tag of a page's content, suffixed with the page's name: `onboardingPage-WELCOME`. */
fun onboardingPageTag(page: OnboardingPage): String = "onboardingPage-${page.name}"

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    onOpenImport: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnboardingContent(
        state = state,
        onNext = viewModel::next,
        onBack = viewModel::back,
        onFinish = { viewModel.finish(onFinished) },
        onOpenImport = onOpenImport,
        onNotificationsToggled = viewModel::onNotificationsToggled,
        onDeviceBackupToggled = viewModel::onDeviceBackupToggled,
        onOpenSystemNotificationSettings = viewModel::openSystemNotificationSettings,
        onSaveKey = viewModel::saveKey,
        onUseMuvissKey = viewModel::useMuvissKey,
        onDismissKeyProblem = viewModel::dismissKeyProblem,
        onGenreMutedToggled = viewModel::onGenreMutedToggled,
    )
}

/**
 * Stateless so the screenshots render exactly what ships. Skip is on every page but the last,
 * including the first: nothing here is required, and a person who skips keeps whatever they set.
 */
@Composable
internal fun OnboardingContent(
    state: OnboardingUiState,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
    onOpenImport: () -> Unit,
    onNotificationsToggled: (Boolean) -> Unit,
    onDeviceBackupToggled: (Boolean) -> Unit,
    onOpenSystemNotificationSettings: () -> Unit,
    onSaveKey: (String, () -> Unit) -> Unit,
    onUseMuvissKey: (() -> Unit) -> Unit,
    onDismissKeyProblem: () -> Unit,
    onGenreMutedToggled: (MutedGenreChoice, Boolean) -> Unit = { _, _ -> },
) {
    val page = state.page ?: return
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = MuvissSpacing.xl, vertical = MuvissSpacing.l),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (!state.isLast) {
                TextButton(onClick = onFinish, modifier = Modifier.testTag(ONBOARDING_SKIP_TAG)) { Text(stringResource(Res.string.onboarding_skip)) }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag(onboardingPageTag(page)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PageContent(
                page = page,
                state = state,
                onOpenImport = onOpenImport,
                onNotificationsToggled = onNotificationsToggled,
                onDeviceBackupToggled = onDeviceBackupToggled,
                onOpenSystemNotificationSettings = onOpenSystemNotificationSettings,
                onSaveKey = onSaveKey,
                onUseMuvissKey = onUseMuvissKey,
                onDismissKeyProblem = onDismissKeyProblem,
                onGenreMutedToggled = onGenreMutedToggled,
            )
        }
        Dots(count = state.pages.size, current = state.index)
        Spacer(Modifier.height(MuvissSpacing.l))
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (state.isFirst) {
                Box(Modifier.size(1.dp))
            } else {
                TextButton(onClick = onBack, modifier = Modifier.testTag(ONBOARDING_BACK_TAG)) { Text(stringResource(Res.string.onboarding_back)) }
            }
            if (state.isLast) {
                Button(onClick = onFinish, modifier = Modifier.testTag(ONBOARDING_START_TAG)) { Text(stringResource(Res.string.onboarding_start)) }
            } else {
                Button(onClick = onNext, modifier = Modifier.testTag(ONBOARDING_NEXT_TAG)) { Text(stringResource(Res.string.onboarding_next)) }
            }
        }
    }
}

@Composable
private fun PageContent(
    page: OnboardingPage,
    state: OnboardingUiState,
    onOpenImport: () -> Unit,
    onNotificationsToggled: (Boolean) -> Unit,
    onDeviceBackupToggled: (Boolean) -> Unit,
    onOpenSystemNotificationSettings: () -> Unit,
    onSaveKey: (String, () -> Unit) -> Unit,
    onUseMuvissKey: (() -> Unit) -> Unit,
    onDismissKeyProblem: () -> Unit,
    onGenreMutedToggled: (MutedGenreChoice, Boolean) -> Unit,
) {
    when (page) {
        OnboardingPage.WELCOME -> Plain(MuvissIcons.Library, Res.string.onboarding_welcome_title, Res.string.onboarding_welcome_body)

        OnboardingPage.LIBRARY -> Plain(MuvissIcons.Check, Res.string.onboarding_library_title, Res.string.onboarding_library_body)

        OnboardingPage.WATCH_NEXT -> Plain(MuvissIcons.WatchNext, Res.string.onboarding_watch_next_title, Res.string.onboarding_watch_next_body) {
            // The widget exists on Android and iOS only; saying so elsewhere would promise a thing that is not there.
            if (platformHasHomeWidgets) PageText(stringResource(Res.string.onboarding_watch_next_widget))
        }

        OnboardingPage.DISCOVER -> Plain(MuvissIcons.Search, Res.string.onboarding_discover_title, Res.string.onboarding_discover_body)

        OnboardingPage.IMPORT -> Plain(MuvissIcons.AddToList, Res.string.onboarding_import_title, Res.string.onboarding_import_body) {
            OutlinedButton(onClick = onOpenImport) { Text(stringResource(Res.string.onboarding_import_action)) }
        }

        OnboardingPage.DEVICE_BACKUP -> Plain(MuvissIcons.Library, Res.string.onboarding_backup_title, Res.string.onboarding_backup_body) {
            // The whole row is the switch, so a screen reader announces its label (#164, as Settings' SwitchRow).
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = state.deviceBackupEnabled, role = Role.Switch, onValueChange = onDeviceBackupToggled)
                    .testTag(ONBOARDING_DEVICE_BACKUP_SWITCH_TAG),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.onboarding_backup_switch), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.deviceBackupEnabled, onCheckedChange = null)
            }
        }

        OnboardingPage.NOTIFICATIONS -> Plain(MuvissIcons.Bell, Res.string.onboarding_notifications_title, Res.string.onboarding_notifications_body) {
            // The whole row is the switch, so a screen reader announces its label (#164, as Settings' SwitchRow).
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = state.notificationsEnabled, role = Role.Switch, onValueChange = onNotificationsToggled)
                    .testTag(ONBOARDING_NOTIFICATIONS_SWITCH_TAG),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.onboarding_notifications_switch), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.notificationsEnabled, onCheckedChange = null)
            }
            if (state.notificationsDenied) {
                PageText(stringResource(Res.string.onboarding_notifications_blocked), error = true)
                TextButton(onClick = onOpenSystemNotificationSettings) { Text(stringResource(Res.string.onboarding_notifications_open_settings)) }
            }
        }

        OnboardingPage.TMDB_KEY -> {
            var open by remember { mutableStateOf(false) }
            Plain(MuvissIcons.Settings, Res.string.onboarding_key_title, Res.string.onboarding_key_body) {
                val own = state.keySource == TmdbDataSource.YOURS
                PageText(stringResource(if (own) Res.string.onboarding_key_yours else Res.string.onboarding_key_muviss))
                OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag(ONBOARDING_KEY_BUTTON_TAG)) {
                    Text(stringResource(if (own) Res.string.onboarding_key_change else Res.string.onboarding_key_use_own))
                }
            }
            if (open) {
                TmdbKeyDialog(
                    title = stringResource(Res.string.onboarding_key_title),
                    usingOwnKey = state.keySource == TmdbDataSource.YOURS,
                    checking = state.keyChecking,
                    problem = state.keyProblem,
                    onSave = { raw -> onSaveKey(raw) { open = false } },
                    onUseMuviss = { onUseMuvissKey { open = false } },
                    onDismiss = {
                        open = false
                        onDismissKeyProblem()
                    },
                )
            }
        }

        OnboardingPage.MUTED_GENRES -> Plain(MuvissIcons.SearchOff, Res.string.onboarding_muted_title, Res.string.onboarding_muted_body) {
            MutedGenreChoice.entries.forEach { choice ->
                MutedGenreRow(choice, muted = choice in state.mutedGenres, posters = state.genrePosters[choice].orEmpty(), onToggle = { onGenreMutedToggled(choice, it) })
            }
            Spacer(Modifier.height(MuvissSpacing.s))
            PageText(stringResource(Res.string.onboarding_muted_more))
        }

        OnboardingPage.DONE -> Plain(MuvissIcons.CheckCircle, Res.string.onboarding_done_title, Res.string.onboarding_done_body)
    }
}

@Composable
private fun Plain(
    icon: ImageVector,
    title: org.jetbrains.compose.resources.StringResource,
    body: org.jetbrains.compose.resources.StringResource,
    extra: @Composable () -> Unit = {},
) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
    Spacer(Modifier.height(MuvissSpacing.xl))
    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(MuvissSpacing.m))
    PageText(stringResource(body))
    Spacer(Modifier.height(MuvissSpacing.l))
    extra()
}

@Composable
private fun PageText(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = MuvissSpacing.s),
    )
}

@Composable
private fun Dots(count: Int, current: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/** Test tag of one genre's switch on the Muted Genres page, suffixed with the choice's name. */
fun mutedGenreSwitchTag(choice: MutedGenreChoice): String = "onboardingMuted-${choice.name}"

/**
 * One genre: a switch, a plain-words name, the shows it would hide (always, from the string table),
 * and a strip of real posters when the network supplied them. The whole row is the switch, so it is
 * announced with its name.
 */
@Composable
private fun MutedGenreRow(choice: MutedGenreChoice, muted: Boolean, posters: List<String>, onToggle: (Boolean) -> Unit) {
    val name = stringResource(choice.nameRes())
    Column(
        Modifier
            .fillMaxWidth()
            .toggleable(value = muted, role = Role.Switch, onValueChange = onToggle)
            .padding(vertical = MuvissSpacing.s)
            .testTag(mutedGenreSwitchTag(choice)),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = MuvissSpacing.s)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(choice.examplesRes()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = muted, onCheckedChange = null)
        }
        if (posters.isNotEmpty()) {
            Row(Modifier.padding(top = MuvissSpacing.xs), horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
                posters.take(POSTERS_PER_GENRE).forEach { url ->
                    PosterImage(url = url, title = name, size = PosterSize.Thumbnail, modifier = Modifier.height(64.dp).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small))
                }
            }
        }
    }
}

private const val POSTERS_PER_GENRE = 3

private fun MutedGenreChoice.nameRes(): org.jetbrains.compose.resources.StringResource = when (this) {
    MutedGenreChoice.TALK -> Res.string.onboarding_muted_talk
    MutedGenreChoice.NEWS -> Res.string.onboarding_muted_news
    MutedGenreChoice.REALITY -> Res.string.onboarding_muted_reality
    MutedGenreChoice.SOAP -> Res.string.onboarding_muted_soap
}

private fun MutedGenreChoice.examplesRes(): org.jetbrains.compose.resources.StringResource = when (this) {
    MutedGenreChoice.TALK -> Res.string.onboarding_muted_talk_examples
    MutedGenreChoice.NEWS -> Res.string.onboarding_muted_news_examples
    MutedGenreChoice.REALITY -> Res.string.onboarding_muted_reality_examples
    MutedGenreChoice.SOAP -> Res.string.onboarding_muted_soap_examples
}
