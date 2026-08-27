package com.codingpit.muviss.feature.triage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.MediaType

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
@ReadOnlyComposable
fun styleFor(verdict: TriageVerdict, mediaType: MediaType, fourWay: Boolean): VerdictStyle = when (verdict) {
    TriageVerdict.SKIP -> VerdictStyle(
        label = "Skip",
        icon = MuvissIcons.Skip,
        color = MaterialTheme.colorScheme.error,
        hint = "Swipe left",
    )

    TriageVerdict.LATER -> VerdictStyle(
        label = "Later",
        icon = MuvissIcons.Later,
        color = MaterialTheme.colorScheme.primary,
        // Under THREE_WAY the downward drag is inert, so the hint must not promise it.
        hint = "Swipe right",
    )

    TriageVerdict.WATCHING -> VerdictStyle(
        label = "Watching",
        icon = MuvissIcons.Watching,
        color = MaterialTheme.colorScheme.tertiary,
        // Under THREE_WAY the downward drag is inert, and a tap now opens the
        // title's detail — so the hint has to point at the button instead.
        hint = if (fourWay) "Swipe down" else "Use the button",
    )

    TriageVerdict.CAUGHT_UP -> VerdictStyle(
        label = if (mediaType == MediaType.MOVIE) "Watched" else "Caught up",
        icon = MuvissIcons.CaughtUp,
        color = MaterialTheme.colorScheme.secondary,
        hint = "Swipe up",
    )
}

/** What each verdict actually does to *this* card, spelled out for the screen reader. */
fun explanationFor(verdict: TriageVerdict, mediaType: MediaType): String = when (verdict) {
    TriageVerdict.SKIP -> "Not for you. Nothing is saved, and it won't come up again."

    TriageVerdict.LATER -> "Saved to your library, not started."

    TriageVerdict.WATCHING -> "Saved and marked as started, so it shows up in Progress."

    TriageVerdict.CAUGHT_UP ->
        if (mediaType == MediaType.MOVIE) "Saved and marked as watched." else "Saved with every aired episode ticked."
}

/**
 * As [explanationFor], but for the first-run tutorial, which runs before any
 * card is on screen and so has no media type to branch on. `CAUGHT_UP`
 * therefore has to cover both readings in one line.
 */
fun explanationForTutorial(verdict: TriageVerdict): String = when (verdict) {
    TriageVerdict.CAUGHT_UP ->
        "Saved with every aired episode ticked — or, for a film, marked watched."

    else -> explanationFor(verdict, MediaType.TV)
}

/** The verdict's word in the tutorial, where both readings have to fit. */
fun tutorialLabelFor(verdict: TriageVerdict, style: VerdictStyle): String = if (verdict == TriageVerdict.CAUGHT_UP) "Caught up / Watched" else style.label
