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
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import kotlinx.coroutines.Job
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

    /**
     * Notifications are off and the OS will not ask again (#326): a request would answer "no" at once, so the
     * nudge explains the blocked switch instead of offering a tap that goes nowhere.
     *
     * Both answers are read from the OS now, never from a synchronous cache: on iOS the cache can predate a
     * change the person just made in Settings (#846), and a decision taken on it would show or hide the wrong line.
     */
    private suspend fun refusedForGoodNow(off: Boolean): Boolean = off && !system.canAskAgainNow()

    /**
     * Advanced by every permission decision (a turn-on tap that is taken, its answer, a dismissal). An OS read
     * that began while it was [decisionEpoch] was before the decision and may answer the old state, so it must
     * not write once the epoch has moved (#846 review). Advancing it on the answer too is what stops a read
     * started after a turn-on began, but before its denial landed, from clearing that denial.
     */
    private var decisionEpoch = 0

    /** Numbers each settlement, so the latest one wins even when an older OS read answers last. */
    private var settleSeq = 0

    /** The settlement in flight. Replaced, never queued. Cancelling it is an optimisation: the guards decide. */
    private var settlement: Job? = null

    init {
        onboarding.observeNotificationNudge(mediaId)
            .onEach { offered ->
                _state.update { it.copy(offered = offered) }
                // Only a nudge that is due can need an explanation. Spending it must not re-read the OS: a
                // denial just given would race that read, and clearing it is the resume check's job.
                // Settling runs as its own job so the collector keeps taking offers while the OS is asked.
                if (offered) settle()
            }
            .launchInReporting(viewModelScope)
    }

    fun turnOn() {
        val decision = ++decisionEpoch
        settlement?.cancel()
        viewModelScope.launchReporting {
            if (refusedForGoodNow(system.blockedNow())) {
                commitDecision(decision, explain = true)
                return@launchReporting
            }
            when (onboarding.acceptNotificationNudge()) {
                NotificationNudgeResult.DENIED -> commitDecision(decision, explain = true)
                NotificationNudgeResult.ENABLED -> commitDecision(decision, explain = false)
            }
        }
    }

    /**
     * Asked again each time the screen resumes, because that is how the person returns from system settings.
     * It can set the explanation as well as clear it: a notification switch turned off there while the nudge is
     * still offered must explain itself, not keep offering a tap the OS would refuse. A refusal the person just
     * gave keeps its line after the nudge is spent, for as long as the OS still says off.
     */
    fun recheckPermission() = settle()

    /**
     * Writes the outcome of a decision, if no other decision or dismissal has happened since it began. The write
     * advances the epoch, so every settlement and turn-on that began before it becomes stale.
     */
    private fun commitDecision(decision: Int, explain: Boolean) {
        if (decisionEpoch != decision) return
        ++decisionEpoch
        settlement?.cancel()
        _state.update { it.copy(blocked = explain) }
    }

    /**
     * Sets [NotificationNudgeState.blocked] from the OS as it is now and what the nudge already knows. Writes only
     * if this is still the latest settlement and no decision or dismissal began during its read.
     */
    private fun settle() {
        settlement?.cancel()
        val seq = ++settleSeq
        val epoch = decisionEpoch
        settlement = viewModelScope.launchReporting {
            val off = system.blockedNow()
            val refusedForGood = refusedForGoodNow(off)
            if (seq != settleSeq || epoch != decisionEpoch) return@launchReporting
            _state.update { it.copy(blocked = (it.blocked && off) || (it.offered && refusedForGood)) }
        }
    }

    fun dismiss() {
        ++decisionEpoch
        settlement?.cancel()
        // Synchronous, so a denial from a turn-on still in flight cannot re-show the line after this tap.
        _state.update { it.copy(blocked = false) }
        viewModelScope.launchReporting {
            onboarding.dismissNotificationNudge()
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
    LifecycleResumeEffect(viewModel) {
        viewModel.recheckPermission()
        onPauseOrDispose {}
    }
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
