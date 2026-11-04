package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.tabular

/** Test tag on the pill; the golden and the rendered-contrast test read its bounds through it. */
const val RATING_BADGE_TAG = "ratingBadge"

/**
 * Amber on the pill's scrim. Contrast is checked against the lightest
 * possible poster (white) — see [RatingBadgeScrim] and docs/ACCESSIBILITY.md.
 */
internal val RatingBadgeAmber = Color(0xFFFFCB6B)

/**
 * The scrim behind the badge. Black at 70% opacity: over a white poster it
 * composites to #4D4D4D, where [RatingBadgeAmber] text is 5.6:1 (WCAG AA needs
 * 4.5:1 for 11 sp text). The previous 55% composited to #737373 and only
 * reached 3.2:1 on a white poster. About 64% is the floor (4.5:1), so 70%
 * keeps a margin without hiding more of the artwork than needed.
 */
internal val RatingBadgeScrim: Color = Color.Black.copy(alpha = 0.70f)

/**
 * "★ 4.5" pill overlaid on posters — amber star + tabular number on a dark
 * scrim so it stays legible over any artwork. The scrim and text are the same
 * in both themes: the badge sits on a poster, not on the theme background.
 *
 * [rating] is the stored 1-10 value; it renders on the five-point scale the
 * detail screen's star row uses, so the same title doesn't read "9" in one
 * place and "4.5 stars" in the other. See [RatingScale].
 */
@Composable
fun RatingBadge(
    rating: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = RatingBadgeScrim,
        modifier = modifier.testTag(RATING_BADGE_TAG),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Icon(
                MuvissIcons.Star,
                contentDescription = null,
                tint = RatingBadgeAmber,
                modifier = Modifier.size(10.dp),
            )
            Text(
                text = " ${RatingScale.label(rating)}",
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = RatingBadgeAmber,
            )
        }
    }
}
