package com.codingpit.muviss.feature.nextpick.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.feature.nextpick.api.PickCommitment
import com.codingpit.muviss.feature.nextpick.api.PickCompany
import com.codingpit.muviss.feature.nextpick.api.PickEra
import com.codingpit.muviss.feature.nextpick.api.PickMood
import com.codingpit.muviss.feature.nextpick.api.PickRuntime
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.domain.PickAnswer
import com.codingpit.muviss.feature.nextpick.domain.PickQuestion
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.Res
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_back
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_commitment_ended
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_commitment_limited
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_commitment_ongoing
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_company_alone
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_company_family
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_company_partner
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_era_classic
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_era_new
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_failed
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_format_movie
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_format_show
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_mood_escape
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_mood_light
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_mood_romantic
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_mood_tense
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_mood_thoughtful
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_no_pick
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_offline
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_q_commitment
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_q_company
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_q_era
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_q_format
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_q_mood
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_q_rewatch
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_q_source
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_retry
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_rewatch_no
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_rewatch_yes
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_skip
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_source_either
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_source_mine
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_source_new
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_start_again
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_step
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_time_binge
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_time_evening
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_time_quick
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_title
import com.codingpit.muviss.models.MediaType
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Pick Session screen (EPIC 51, #400): one question at a time, every question skippable, progress and
 * a way back. The result is drawn by [presentation]. Back steps to the previous question and only leaves
 * the screen from the first one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickSessionScreen(
    viewModel: PickSessionViewModel,
    presentation: PickPresentation,
    onLeave: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        // The app shell's ScreenInsets already pads the whole NavHost (safe drawing, top and sides), so the
        // Scaffold must not add its own inset on top of it.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.nextpick_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.back()) onLeave() }) {
                        Icon(MuvissIcons.Back, contentDescription = stringResource(Res.string.nextpick_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                PickSessionUiState.Loading -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))

                is PickSessionUiState.Asking -> AskingBody(current, onAnswer = viewModel::answer, onSkip = viewModel::skipQuestion)

                is PickSessionUiState.Result -> presentation.Content(
                    state = current,
                    callbacks = PickResultCallbacks(
                        onAdd = viewModel::add,
                        onNotForMe = viewModel::notForMe,
                        onSnooze = viewModel::snooze,
                        onAnother = viewModel::another,
                    ),
                    modifier = Modifier.fillMaxSize(),
                )

                is PickSessionUiState.NoPick -> MessageBody(stringResource(Res.string.nextpick_no_pick), Res.string.nextpick_start_again, viewModel::start)

                PickSessionUiState.Failed -> MessageBody(stringResource(Res.string.nextpick_failed), Res.string.nextpick_retry, viewModel::start)
            }
        }
    }
}

@Composable
private fun AskingBody(state: PickSessionUiState.Asking, onAnswer: (PickAnswer) -> Unit, onSkip: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(progress = { state.step.toFloat() / state.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(Res.string.nextpick_step, state.step, state.total), style = MaterialTheme.typography.labelLarge)
        if (state.offline) Text(stringResource(Res.string.nextpick_offline), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(questionTitle(state.question)), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            optionsFor(state.question).forEach { option ->
                FilterChip(selected = false, onClick = { onAnswer(option.answer) }, label = { Text(stringResource(option.label)) })
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) { Text(stringResource(Res.string.nextpick_skip)) }
    }
}

@Composable
private fun MessageBody(message: String, action: StringResource, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onAction) { Text(stringResource(action)) }
    }
}

private class Option(val label: StringResource, val answer: PickAnswer)

private fun questionTitle(question: PickQuestion): StringResource = when (question) {
    PickQuestion.SOURCE -> Res.string.nextpick_q_source
    PickQuestion.FORMAT_AND_TIME -> Res.string.nextpick_q_format
    PickQuestion.MOOD -> Res.string.nextpick_q_mood
    PickQuestion.ANCHOR -> Res.string.nextpick_q_format
    PickQuestion.ERA_AND_SERVICES -> Res.string.nextpick_q_era
    PickQuestion.COMPANY -> Res.string.nextpick_q_company
    PickQuestion.COMMITMENT -> Res.string.nextpick_q_commitment
    PickQuestion.REWATCH -> Res.string.nextpick_q_rewatch
}

/**
 * The chips each question offers, as complete answers. Each chip is one [PickAnswer], so a question's
 * answer is never split across two taps. ANCHOR is never asked in v1 (see the context reader).
 */
private fun optionsFor(question: PickQuestion): List<Option> = when (question) {
    PickQuestion.SOURCE -> listOf(
        Option(Res.string.nextpick_source_mine, PickAnswer.Source(PickSource.MY_LIST)),
        Option(Res.string.nextpick_source_new, PickAnswer.Source(PickSource.SOMETHING_NEW)),
        Option(Res.string.nextpick_source_either, PickAnswer.Source(PickSource.EITHER)),
    )

    PickQuestion.FORMAT_AND_TIME -> listOf(
        Option(Res.string.nextpick_format_movie, PickAnswer.FormatAndTime(MediaType.MOVIE, null)),
        Option(Res.string.nextpick_format_show, PickAnswer.FormatAndTime(MediaType.TV, null)),
        Option(Res.string.nextpick_time_quick, PickAnswer.FormatAndTime(null, PickRuntime.QUICK)),
        Option(Res.string.nextpick_time_evening, PickAnswer.FormatAndTime(null, PickRuntime.EVENING)),
        Option(Res.string.nextpick_time_binge, PickAnswer.FormatAndTime(null, PickRuntime.BINGE)),
    )

    PickQuestion.MOOD -> listOf(
        Option(Res.string.nextpick_mood_light, PickAnswer.Mood(setOf(PickMood.LIGHT))),
        Option(Res.string.nextpick_mood_tense, PickAnswer.Mood(setOf(PickMood.TENSE))),
        Option(Res.string.nextpick_mood_thoughtful, PickAnswer.Mood(setOf(PickMood.THOUGHTFUL))),
        Option(Res.string.nextpick_mood_escape, PickAnswer.Mood(setOf(PickMood.ESCAPE))),
        Option(Res.string.nextpick_mood_romantic, PickAnswer.Mood(setOf(PickMood.ROMANTIC))),
    )

    PickQuestion.ERA_AND_SERVICES -> listOf(
        Option(Res.string.nextpick_era_new, PickAnswer.EraAndServices(PickEra.NEW, null, false)),
        Option(Res.string.nextpick_era_classic, PickAnswer.EraAndServices(PickEra.CLASSIC, null, false)),
    )

    PickQuestion.COMPANY -> listOf(
        Option(Res.string.nextpick_company_alone, PickAnswer.Company(PickCompany.ALONE)),
        Option(Res.string.nextpick_company_partner, PickAnswer.Company(PickCompany.PARTNER)),
        Option(Res.string.nextpick_company_family, PickAnswer.Company(PickCompany.FAMILY)),
    )

    PickQuestion.COMMITMENT -> listOf(
        Option(Res.string.nextpick_commitment_limited, PickAnswer.Commitment(PickCommitment.LIMITED)),
        Option(Res.string.nextpick_commitment_ended, PickAnswer.Commitment(PickCommitment.ENDED)),
        Option(Res.string.nextpick_commitment_ongoing, PickAnswer.Commitment(PickCommitment.ONGOING)),
    )

    PickQuestion.REWATCH -> listOf(
        Option(Res.string.nextpick_rewatch_yes, PickAnswer.Rewatch(true)),
        Option(Res.string.nextpick_rewatch_no, PickAnswer.Rewatch(false)),
    )

    PickQuestion.ANCHOR -> emptyList()
}
