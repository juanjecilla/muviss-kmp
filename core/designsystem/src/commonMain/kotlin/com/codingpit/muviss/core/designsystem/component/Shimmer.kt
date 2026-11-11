package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.codingpit.shimmer.ShimmerHost
import com.codingpit.shimmer.shimmer

/**
 * A loading area: every skeleton below it shares one sweep, so the highlight
 * travels across the screen in turn (`:lib:shimmer` does the drawing). [animate]
 * is the reduced-motion switch (#394, EPIC 52): false draws every placeholder as
 * a flat surface and runs no animation.
 */
@Composable
fun SkeletonHost(animate: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ShimmerHost(modifier = modifier, animate = animate, content = content)
}

/** The Muviss skin of the shimmer: the surface tones, read from the theme. */
@Composable
private fun Modifier.skeletonShimmer(): Modifier {
    val colors = MaterialTheme.colorScheme
    return shimmer(base = colors.surfaceContainerLow, highlight = colors.surfaceContainerHigh)
}

@Composable
fun PosterSkeleton(modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(MaterialTheme.shapes.medium)
            .skeletonShimmer(),
    )
}

@Composable
fun TextLineSkeleton(modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(12.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .skeletonShimmer(),
    )
}

@Composable
fun RowSkeleton(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(56.dp)
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.small)
                .skeletonShimmer(),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 6.dp)) {
            TextLineSkeleton(Modifier.fillMaxWidth())
            TextLineSkeleton(Modifier.fillMaxWidth(0.7f).padding(top = 9.dp))
            TextLineSkeleton(Modifier.fillMaxWidth(0.45f).padding(top = 9.dp))
        }
    }
}

/**
 * A poster card's shape before its artwork loads: the poster and the caption under it. The caption is sized by the
 * same two-line text the loaded card shows (`PosterCard`: `titleSmall`, `minLines = 2`), so a carousel keeps its
 * height when the cards arrive. The text is invisible and carries no semantics; the bar is drawn over it.
 */
@Composable
fun PosterCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier) {
        PosterSkeleton(Modifier.fillMaxWidth())
        Box(Modifier.padding(top = 6.dp).fillMaxWidth()) {
            Text(
                text = " ",
                style = MaterialTheme.typography.titleSmall,
                minLines = POSTER_TITLE_LINES,
                maxLines = POSTER_TITLE_LINES,
                color = Color.Transparent,
                modifier = Modifier.clearAndSetSemantics { },
            )
            TextLineSkeleton(Modifier.fillMaxWidth(0.8f).align(Alignment.TopStart))
        }
    }
}

/** The loaded poster card's title is two lines (`PosterCard`'s `TITLE_LINES`). */
private const val POSTER_TITLE_LINES = 2

/** A genre chip's shape (a 32 dp pill, the height of a suggestion chip) before the genres arrive. */
@Composable
fun ChipSkeleton(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(96.dp)
            .height(32.dp)
            .clip(CircleShape)
            .skeletonShimmer(),
    )
}
