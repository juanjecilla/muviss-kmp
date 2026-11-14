package com.codingpit.shimmer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/** One sweep's full length, in milliseconds, when a host does not say otherwise. */
const val DEFAULT_SHIMMER_DURATION_MILLIS: Int = 1400

/** The highlight band's width as a fraction of the host's width, when a host does not say otherwise. */
const val DEFAULT_SHIMMER_BAND_FRACTION: Float = 0.8f

/**
 * Runs one sweep for every [Modifier.shimmer] below it, so the highlight
 * travels across the whole screen in turn instead of each placeholder running
 * its own animation. The band is positioned in window coordinates: an item
 * draws the part of the band that falls on it, so neighbours share one edge.
 *
 * [angle] is the sweep's direction in degrees: 0 left to right (the default),
 * 90 top to bottom, 45 diagonally. Every placeholder under the host follows it.
 *
 * [animate] false draws every placeholder as a flat fill and runs no animation
 * at all. Hosts pass the reduced-motion switch here.
 */
@Composable
fun ShimmerHost(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    durationMillis: Int = DEFAULT_SHIMMER_DURATION_MILLIS,
    bandFraction: Float = DEFAULT_SHIMMER_BAND_FRACTION,
    angle: Float = 0f,
    content: @Composable () -> Unit,
) {
    val progress: State<Float> = if (animate) rememberSweep(durationMillis) else remember { mutableFloatStateOf(0f) }
    val geometry = remember { ShimmerGeometry() }
    val wave = ShimmerWave(progress = progress, animate = animate, bandFraction = bandFraction, angle = angle, geometry = geometry)
    Box(
        modifier.onGloballyPositioned { coordinates ->
            val topLeft = coordinates.positionInRoot()
            geometry.left = topLeft.x
            geometry.top = topLeft.y
            geometry.width = coordinates.size.width.toFloat()
            geometry.height = coordinates.size.height.toFloat()
        },
    ) {
        CompositionLocalProvider(LocalShimmerWave provides wave, content = content)
    }
}

/**
 * Where a host sits, read by its placeholders at draw time. Plain fields, not
 * state: a layout change must not recompose the placeholders, and the sweep
 * already redraws them every frame while it runs.
 */
internal class ShimmerGeometry {
    var left: Float = 0f
    var top: Float = 0f
    var width: Float = 0f
    var height: Float = 0f
}

@Immutable
internal data class ShimmerWave(
    val progress: State<Float>,
    val animate: Boolean,
    val bandFraction: Float,
    val angle: Float,
    val geometry: ShimmerGeometry,
)

internal val LocalShimmerWave = staticCompositionLocalOf<ShimmerWave?> { null }

@Composable
internal fun rememberSweep(durationMillis: Int): State<Float> {
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerSweep",
    )
}
