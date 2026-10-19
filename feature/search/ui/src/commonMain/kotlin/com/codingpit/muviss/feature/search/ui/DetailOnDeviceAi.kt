package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import com.codingpit.muviss.core.designsystem.ai.LocalOnDeviceAi
import com.codingpit.muviss.core.designsystem.ai.OnDeviceFeature
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.detail_ai_describe
import com.codingpit.muviss.feature.search.ui.generated.resources.detail_ai_described_label
import com.codingpit.muviss.feature.search.ui.generated.resources.detail_ai_failed
import com.codingpit.muviss.feature.search.ui.generated.resources.detail_ai_shorten
import com.codingpit.muviss.feature.search.ui.generated.resources.detail_ai_shortened_label
import com.codingpit.muviss.feature.search.ui.generated.resources.detail_ai_working
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Where one on-device request stands. Nothing runs until the user asks. */
private sealed interface Step {
    data object Idle : Step

    data object Working : Step

    data class Done(val text: String) : Step

    data object Failed : Step
}

/**
 * Two things a model on the phone can do for accessibility, each behind a button the user presses:
 *
 *  - **Short & simple**: a shorter synopsis, for people who find a block of text hard to read.
 *  - **Describe image**: what is in the backdrop, for people who cannot see it (English only).
 *
 * Shown only when the device can do it ([OnDeviceFeature]); on every other device it draws nothing. The result is
 * announced (live region) and labelled as machine-made, because a model can describe what is not there.
 */
@Composable
internal fun OnDeviceAiTools(overview: String?, backdropUrl: String?, modifier: Modifier = Modifier) {
    val ai = LocalOnDeviceAi.current
    val scope = rememberCoroutineScope()
    val language = Locale.current.language

    var canShorten by remember(ai, overview) { mutableStateOf(false) }
    var canDescribe by remember(ai, backdropUrl) { mutableStateOf(false) }
    LaunchedEffect(ai, overview, backdropUrl) {
        canShorten = overview != null && ai.isAvailable(OnDeviceFeature.Shorten)
        canDescribe = backdropUrl != null && ai.isAvailable(OnDeviceFeature.ImageDescription)
    }

    var shortened by remember(overview) { mutableStateOf<Step>(Step.Idle) }
    var described by remember(backdropUrl) { mutableStateOf<Step>(Step.Idle) }
    if (!canShorten && !canDescribe) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
            if (canShorten && overview != null) {
                TextButton(
                    onClick = {
                        if (shortened is Step.Idle || shortened is Step.Failed) {
                            shortened = Step.Working
                            scope.launch { shortened = ai.shorten(overview, language)?.let(Step::Done) ?: Step.Failed }
                        } else {
                            shortened = Step.Idle
                        }
                    },
                ) { Text(stringResource(Res.string.detail_ai_shorten)) }
            }
            if (canDescribe && backdropUrl != null) {
                TextButton(
                    onClick = {
                        if (described is Step.Idle || described is Step.Failed) {
                            described = Step.Working
                            scope.launch { described = ai.describeImage(backdropUrl)?.let(Step::Done) ?: Step.Failed }
                        } else {
                            described = Step.Idle
                        }
                    },
                ) { Text(stringResource(Res.string.detail_ai_describe)) }
            }
        }
        Result(shortened, labelFor = { stringResource(Res.string.detail_ai_shortened_label) })
        Result(described, labelFor = { stringResource(Res.string.detail_ai_described_label) })
    }
}

@Composable
private fun Result(step: Step, labelFor: @Composable () -> String) {
    // A polite live region: the answer is read when it arrives, without moving the user's focus.
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
        when (step) {
            Step.Idle -> Unit

            Step.Working -> Text(stringResource(Res.string.detail_ai_working), style = MaterialTheme.typography.bodySmall)

            Step.Failed -> Text(stringResource(Res.string.detail_ai_failed), style = MaterialTheme.typography.bodySmall)

            is Step.Done -> {
                Text(labelFor(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(step.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
