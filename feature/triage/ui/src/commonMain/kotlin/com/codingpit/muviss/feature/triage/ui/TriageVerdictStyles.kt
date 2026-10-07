package com.codingpit.muviss.feature.triage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.feature.triage.ui.generated.resources.Res
import com.codingpit.muviss.feature.triage.ui.generated.resources.explain_caught_up
import com.codingpit.muviss.feature.triage.ui.generated.resources.explain_caught_up_tutorial
import com.codingpit.muviss.feature.triage.ui.generated.resources.explain_later
import com.codingpit.muviss.feature.triage.ui.generated.resources.explain_skip
import com.codingpit.muviss.feature.triage.ui.generated.resources.explain_watched
import com.codingpit.muviss.feature.triage.ui.generated.resources.explain_watching
import com.codingpit.muviss.feature.triage.ui.generated.resources.hint_button
import com.codingpit.muviss.feature.triage.ui.generated.resources.hint_down
import com.codingpit.muviss.feature.triage.ui.generated.resources.hint_left
import com.codingpit.muviss.feature.triage.ui.generated.resources.hint_right
import com.codingpit.muviss.feature.triage.ui.generated.resources.hint_up
import com.codingpit.muviss.feature.triage.ui.generated.resources.verdict_caught_up
import com.codingpit.muviss.feature.triage.ui.generated.resources.verdict_caught_up_or_watched
import com.codingpit.muviss.feature.triage.ui.generated.resources.verdict_later
import com.codingpit.muviss.feature.triage.ui.generated.resources.verdict_skip
import com.codingpit.muviss.feature.triage.ui.generated.resources.verdict_watched
import com.codingpit.muviss.feature.triage.ui.generated.resources.verdict_watching
import com.codingpit.muviss.models.MediaType
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * How each verdict presents: the word on its button, the direction hint, the
 * glyph, and the colour the card tints toward while it is dragged that way.
 * One place, so the button row, the drag overlay and the tutorial can never
 * drift apart.
 */
data class VerdictStyle(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val hint: String,
)

/**
 * The verdict as it presents for one card.
 *
 * [mediaType] only changes `CAUGHT_UP`, and only its wording: a film has a
 * single element, so "caught up" says nothing, while "Watched" is exactly the
 * `WatchStatus` the verdict's ticks derive. The verdict itself is one value —
 * the decision log and the sync payload are unchanged (ADR 0010).
 */
@Composable
fun styleFor(verdict: TriageVerdict, mediaType: MediaType, fourWay: Boolean): VerdictStyle = when (verdict) {
    TriageVerdict.SKIP -> VerdictStyle(
        label = stringResource(Res.string.verdict_skip),
        icon = MuvissIcons.Skip,
        color = MaterialTheme.colorScheme.error,
        hint = stringResource(Res.string.hint_left),
    )

    TriageVerdict.LATER -> VerdictStyle(
        label = stringResource(Res.string.verdict_later),
        icon = MuvissIcons.Later,
        color = MaterialTheme.colorScheme.primary,
        // Under THREE_WAY the downward drag is inert, so the hint must not promise it.
        hint = stringResource(Res.string.hint_right),
    )

    TriageVerdict.WATCHING -> VerdictStyle(
        label = stringResource(Res.string.verdict_watching),
        icon = MuvissIcons.Watching,
        color = MaterialTheme.colorScheme.tertiary,
        // Under THREE_WAY the downward drag is inert, and a tap now opens the
        // title's detail — so the hint has to point at the button instead.
        hint = stringResource(if (fourWay) Res.string.hint_down else Res.string.hint_button),
    )

    TriageVerdict.CAUGHT_UP -> VerdictStyle(
        label = stringResource(if (mediaType == MediaType.MOVIE) Res.string.verdict_watched else Res.string.verdict_caught_up),
        icon = MuvissIcons.CaughtUp,
        color = MaterialTheme.colorScheme.secondary,
        hint = stringResource(Res.string.hint_up),
    )
}

/** What each verdict actually does to *this* card, spelled out for the screen reader. */
fun explanationFor(verdict: TriageVerdict, mediaType: MediaType): StringResource = when (verdict) {
    TriageVerdict.SKIP -> Res.string.explain_skip

    TriageVerdict.LATER -> Res.string.explain_later

    TriageVerdict.WATCHING -> Res.string.explain_watching

    TriageVerdict.CAUGHT_UP ->
        if (mediaType == MediaType.MOVIE) Res.string.explain_watched else Res.string.explain_caught_up
}

/**
 * As [explanationFor], but for the first-run tutorial, which runs before any
 * card is on screen and so has no media type to branch on. `CAUGHT_UP`
 * therefore has to cover both readings in one line.
 */
fun explanationForTutorial(verdict: TriageVerdict): StringResource = when (verdict) {
    TriageVerdict.CAUGHT_UP -> Res.string.explain_caught_up_tutorial
    else -> explanationFor(verdict, MediaType.TV)
}

/** The verdict's word in the tutorial, where both readings have to fit. */
@Composable
fun tutorialLabelFor(verdict: TriageVerdict, style: VerdictStyle): String = if (verdict == TriageVerdict.CAUGHT_UP) stringResource(Res.string.verdict_caught_up_or_watched) else style.label
