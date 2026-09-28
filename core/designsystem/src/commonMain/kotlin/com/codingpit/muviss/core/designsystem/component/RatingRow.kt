package com.codingpit.muviss.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import kotlinx.coroutines.delay

/** Test tag prefix; the star at index `i` is tagged `"ratingStar$i"`. */
const val RATING_STAR_TAG_PREFIX = "ratingStar"

private const val STAGGER_MS = 20L
private val STAR_TOUCH_TARGET = 44.dp
private val STAR_GLYPH = 24.dp

/**
 * The five-star rating row. Each star has a 44dp touch target (glyph is 24dp)
 * split down the middle: the left half is the half-star value, the right half
 * the whole one. Tapping the half already selected clears the rating.
 *
 * The value handed to [onRate] is still 1-10 — see [RatingScale] for why the
 * display scale and the stored scale differ, and why that needed no change
 * below the UI. Stars fill left-to-right with a 20ms stagger pop when the
 * rating changes.
 *
 * Accessibility-wise this is one adjustable node ("Your rating, 3.5 of 5"),
 * not five buttons: each star clears its own semantics, keeping only a test
 * tag.
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
            contentDescription = if (rating != null) {
                "Your rating, ${RatingScale.label(rating)} of ${RatingScale.STAR_COUNT}, adjustable"
            } else {
                "Your rating, not set, adjustable"
            }
            progressBarRangeInfo = ProgressBarRangeInfo(
                current = rating?.let { RatingScale.starsOf(it) } ?: 0f,
                range = 0f..RatingScale.STAR_COUNT.toFloat(),
                steps = RatingScale.MAX_STORED - 1,
            )
        },
    ) {
        repeat(RatingScale.STAR_COUNT) { index ->
            RatingStar(
                fill = RatingScale.fillOf(rating, index),
                staggerIndex = index,
                tag = "$RATING_STAR_TAG_PREFIX$index",
                onTapHalf = { leftHalf ->
                    val tapped = RatingScale.valueForTap(index, leftHalf)
                    if (rating == tapped) onClear() else onRate(tapped)
                },
            )
        }
    }
}

@Composable
private fun RatingStar(
    fill: StarFill,
    staggerIndex: Int,
    tag: String,
    onTapHalf: (leftHalf: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(fill) {
        if (fill != StarFill.EMPTY) {
            delay(staggerIndex * STAGGER_MS)
            scale.snapTo(0.8f)
            scale.animateTo(1f)
        }
    }
    // `RatingRow` builds a fresh `onTapHalf` closure every recomposition,
    // capturing whatever `rating` is current at that point — but
    // `pointerInput(Unit)`'s coroutine only launches once (this star's slot
    // is not recycled across items, unlike the triage card stack, so keying
    // it on the current rating would restart mid-gesture for no reason). Left
    // as a plain capture, the tap handler would stay bound to the `rating`
    // that was current the first time this star composed — so tapping the
    // already-selected half again (meant to clear it, see
    // `tapping_the_half_already_selected_clears_the_rating` below) would
    // silently re-apply the *original* rating instead, forever, after the
    // very first change. `rememberUpdatedState` is the standard fix: it lets
    // the long-lived coroutine keep running while always reading the latest
    // `onTapHalf` on the next tap.
    val currentOnTapHalf by rememberUpdatedState(onTapHalf)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(STAR_TOUCH_TARGET)
            .pointerInput(Unit) {
                detectTapGestures { offset -> currentOnTapHalf(offset.x < size.width / 2f) }
            }
            .clearAndSetSemantics { testTag = tag },
    ) {
        Box(
            Modifier.size(STAR_GLYPH).graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        ) {
            Icon(
                imageVector = if (fill == StarFill.FULL) MuvissIcons.Star else MuvissIcons.StarOutline,
                contentDescription = null,
                tint = if (fill == StarFill.FULL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(STAR_GLYPH),
            )
            if (fill == StarFill.HALF) {
                // A filled star laid exactly over the outline and clipped at
                // its own midpoint, so the amber is the left half *of that
                // star* rather than a separate glyph that has to be kept in
                // visual sync with STAR_PATH by hand.
                //
                // The clip is a draw-time `clipRect` rather than a smaller
                // parent box around an oversized child: sizing the wrapper to
                // half a star makes the icon inside it a layout child that no
                // longer shares the outline's origin, and it renders visibly
                // offset from the star it is supposed to be filling.
                Icon(
                    imageVector = MuvissIcons.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(STAR_GLYPH)
                        .drawWithContent {
                            clipRect(right = size.width / 2f) { this@drawWithContent.drawContent() }
                        },
                )
            }
        }
    }
}
