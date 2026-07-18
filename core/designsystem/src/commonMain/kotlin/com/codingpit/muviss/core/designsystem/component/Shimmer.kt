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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Loading shimmer per the design doc: a slow gradient sweep over raised
 * surface tones. Apply to any placeholder shape, or use the ready-made
 * [PosterSkeleton]/[RowSkeleton]/[TextLineSkeleton].
 */
fun Modifier.shimmer(): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    val highlight = MaterialTheme.colorScheme.surfaceContainerHigh
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerSweep",
    )
    drawWithCache {
        val width = size.width
        val brush = Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(progress * width, 0f),
            end = Offset((progress + 1f) * width, size.height),
        )
        onDrawBehind { drawRect(brush) }
    }
}

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
