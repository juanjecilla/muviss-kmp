package com.codingpit.muviss.feature.triage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.feature.triage.api.TriageVerdict

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

@Composable
@ReadOnlyComposable
fun styleFor(verdict: TriageVerdict, fourWay: Boolean): VerdictStyle = when (verdict) {
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
        hint = "Swipe right",
    )

    TriageVerdict.WATCHING -> VerdictStyle(
        label = "Watching",
        icon = MuvissIcons.Watching,
        color = MaterialTheme.colorScheme.tertiary,
        // Under THREE_WAY the downward drag is inert, so the hint must not promise it.
        hint = if (fourWay) "Swipe down" else "Tap",
    )

    TriageVerdict.CAUGHT_UP -> VerdictStyle(
        label = "Caught up",
        icon = MuvissIcons.CaughtUp,
        color = MaterialTheme.colorScheme.secondary,
        hint = "Swipe up",
    )
}

/** What each verdict actually does, spelled out for the tutorial. */
fun explanationFor(verdict: TriageVerdict): String = when (verdict) {
    TriageVerdict.SKIP -> "Not for you. Nothing is saved, and it won't come up again."
    TriageVerdict.LATER -> "Saved to your library, not started."
    TriageVerdict.WATCHING -> "Saved and marked as started, so it shows up in Progress."
    TriageVerdict.CAUGHT_UP -> "Saved with every aired episode ticked."
}
