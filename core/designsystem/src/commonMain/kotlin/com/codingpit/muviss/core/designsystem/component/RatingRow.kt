package com.codingpit.muviss.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import kotlinx.coroutines.delay

private const val STAR_COUNT = 10
private const val STAGGER_MS = 20L

/**
 * The 10-star rating row. Each star has a 44dp touch target (glyph is 24dp);
 * tapping the current rating again clears it. Stars fill left→right with a
 * 20ms stagger pop when the rating changes. Accessibility-wise this is one
 * adjustable node ("Your rating, 7 of 10"), not ten buttons — child stars'
 * semantics are cleared.
 */
@Composable
fun RatingRow(
    rating: Int?,
    onRate: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics {
            contentDescription = if (rating != null) "Your rating, $rating of 10, adjustable" else "Your rating, not set, adjustable"
            progressBarRangeInfo = ProgressBarRangeInfo((rating ?: 0).toFloat(), 0f..STAR_COUNT.toFloat(), STAR_COUNT)
        },
    ) {
        repeat(STAR_COUNT) { index ->
            val starNumber = index + 1
            val lit = rating != null && starNumber <= rating
            RatingStar(
                lit = lit,
                staggerIndex = index,
                onClick = { if (rating == starNumber) onClear() else onRate(starNumber) },
            )
        }
    }
}

@Composable
private fun RatingStar(
    lit: Boolean,
    staggerIndex: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(lit) {
        if (lit) {
            delay(staggerIndex * STAGGER_MS)
            scale.snapTo(0.8f)
            scale.animateTo(1f)
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .clearAndSetSemantics {},
    ) {
        Icon(
            imageVector = if (lit) MuvissIcons.Star else MuvissIcons.StarOutline,
            contentDescription = null,
            tint = if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(24.dp).graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        )
    }
}
