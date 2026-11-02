package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.notifications.SystemNotificationSettings
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.onboarding.api.NotificationNudgeResult
import com.codingpit.muviss.feature.onboarding.api.OnboardingApi
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.nudge_blocked
import com.codingpit.muviss.feature.search.ui.generated.resources.nudge_dismiss
import com.codingpit.muviss.feature.search.ui.generated.resources.nudge_open_settings
import com.codingpit.muviss.feature.search.ui.generated.resources.nudge_question
import com.codingpit.muviss.feature.search.ui.generated.resources.nudge_turn_on
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.jetbrains.compose.resources.stringResource

const val NUDGE_BANNER_TAG = "notificationNudge"
const val NUDGE_TURN_ON_TAG = "notificationNudgeTurnOn"
const val NUDGE_DISMISS_TAG = "notificationNudgeDismiss"

data class NotificationNudgeState(
    /** Onboarding says this title should offer the nudge. */
    val offered: Boolean = false,
    /** The user said yes and the OS said no: explain instead of vanishing. */
    val blocked: Boolean = false,
) {
    val visible: Boolean get() = offered || blocked
}

/**
 * The Notification Nudge on a title's Detail (EPIC 47): when notifications are off and this is the
 * user's first saved TV show, offer once to turn them on. Whether it is due, and that it is only ever
 * once, is onboarding's to say; this just shows it and forwards the two answers.
 */
class NotificationNudgeViewModel(
    mediaId: MediaId,
    private val onboarding: OnboardingApi,
    private val system: SystemNotificationSettings,
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationNudgeState())
    val state: StateFlow<NotificationNudgeState> = _state.asStateFlow()

    init {
        onboarding.observeNotificationNudge(mediaId)
            .onEach { offered -> _state.update { it.copy(offered = offered) } }
            .launchInReporting(viewModelScope)
    }

    fun turnOn() {
        viewModelScope.launchReporting {
            if (onboarding.acceptNotificationNudge() == NotificationNudgeResult.DENIED) {
                _state.update { it.copy(blocked = true) }
            }
        }
    }

    fun dismiss() {
        viewModelScope.launchReporting {
            onboarding.dismissNotificationNudge()
            _state.update { it.copy(blocked = false) }
        }
    }

    fun openSystemSettings() = system.open()
}

/** Stateless body, so a test or a golden can render each state. */
@Composable
internal fun NotificationNudgeBanner(
    title: String,
    blocked: Boolean,
    onTurnOn: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag(NUDGE_BANNER_TAG),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = MuvissSpacing.m, top = MuvissSpacing.xs, bottom = MuvissSpacing.xs),
        ) {
            Icon(MuvissIcons.Bell, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f).padding(horizontal = MuvissSpacing.s), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
                Text(
                    stringResource(if (blocked) Res.string.nudge_blocked else Res.string.nudge_question, title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (blocked) {
                TextButton(onClick = onOpenSettings) { Text(stringResource(Res.string.nudge_open_settings)) }
            } else {
                TextButton(onClick = onTurnOn, modifier = Modifier.testTag(NUDGE_TURN_ON_TAG)) { Text(stringResource(Res.string.nudge_turn_on)) }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.testTag(NUDGE_DISMISS_TAG)) {
                Icon(MuvissIcons.Close, contentDescription = stringResource(Res.string.nudge_dismiss), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/** The banner wired to its view model; draws nothing while the nudge is not due. */
@Composable
internal fun NotificationNudge(viewModel: NotificationNudgeViewModel, title: String) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.visible) return
    NotificationNudgeBanner(
        title = title,
        blocked = state.blocked,
        onTurnOn = viewModel::turnOn,
        onDismiss = viewModel::dismiss,
        onOpenSettings = viewModel::openSystemSettings,
    )
}
