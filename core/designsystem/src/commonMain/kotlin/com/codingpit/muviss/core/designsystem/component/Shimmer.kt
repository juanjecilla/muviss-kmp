package com.codingpit.muviss.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Loading shimmer per the design doc: a slow gradient sweep over raised
 * surface tones. Apply to any placeholder shape, or use the ready-made
 * [PosterSkeleton]/[RowSkeleton]/[TextLineSkeleton].
 *
 * The band is horizontal and starts and ends each cycle fully off the shape
 * (see [shimmerBandStart]), so the first frame of a cycle looks exactly like
 * the last one and the restart is invisible. An earlier diagonal sweep left a
 * corner half-lit at both ends and visibly jumped every cycle. Progress is
 * read only in the draw phase: the animation redraws the shape every frame
 * but never recomposes it.
 */
fun Modifier.shimmer(): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    val highlight = MaterialTheme.colorScheme.surfaceContainerHigh
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SWEEP_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerSweep",
    )
    drawBehind {
        val band = size.width * BAND_FRACTION
        val bandStart = shimmerBandStart(progress.value, size.width, band)
        drawRect(Brush.horizontalGradient(listOf(base, highlight, base), startX = bandStart, endX = bandStart + band))
    }
}

/**
 * Where the highlight band's left edge sits at [progress] (0..1) of a sweep
 * across a shape [width] wide: from `-band`, wholly left of the shape, to
 * [width], wholly right of it, at constant speed.
 */
internal fun shimmerBandStart(progress: Float, width: Float, band: Float): Float = -band + progress * (width + band)

private const val SWEEP_MILLIS = 1400
private const val BAND_FRACTION = 0.8f

@Composable
fun PosterSkeleton(modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(MaterialTheme.shapes.medium)
            .shimmer(),
    )
}

@Composable
fun TextLineSkeleton(modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(12.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .shimmer(),
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
                .shimmer(),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 6.dp)) {
            TextLineSkeleton(Modifier.fillMaxWidth())
            TextLineSkeleton(Modifier.fillMaxWidth(0.7f).padding(top = 9.dp))
            TextLineSkeleton(Modifier.fillMaxWidth(0.45f).padding(top = 9.dp))
        }
    }
}
