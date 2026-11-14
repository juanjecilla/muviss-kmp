package com.codingpit.shimmer

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Paints a placeholder with a moving highlight: `base`, `highlight`, `base`
 * across a band that sweeps in the direction [angle] gives.
 *
 * [angle] is in degrees: 0 sweeps left to right (the default), 90 top to
 * bottom. Inside a [ShimmerHost] the host's band and angle are used, so
 * neighbours line up and [angle] here is ignored. Outside one the placeholder
 * sweeps across its own bounds at [angle], which is the old per-item behaviour
 * at the default. [animate] false, or a host with `animate = false`, draws
 * `base` as a flat fill.
 */
fun Modifier.shimmer(base: Color, highlight: Color, animate: Boolean = true, angle: Float = 0f): Modifier = composed {
    val hosted = LocalShimmerWave.current
    val ownProgress: State<Float>? = if (hosted == null && animate) rememberSweep(DEFAULT_SHIMMER_DURATION_MILLIS) else null
    val still = remember { mutableFloatStateOf(0f) }
    val progress: State<Float> = hosted?.progress ?: ownProgress ?: still
    val live = animate && (hosted?.animate ?: true)
    val bandFraction = hosted?.bandFraction ?: DEFAULT_SHIMMER_BAND_FRACTION
    val sweepAngle = hosted?.angle ?: angle
    val direction = remember(sweepAngle) { sweepDirection(sweepAngle) }
    val geometry = remember { ShimmerGeometry() }
    val frame = hosted?.geometry

    Modifier
        .onGloballyPositioned { coordinates ->
            val topLeft = coordinates.positionInRoot()
            geometry.left = topLeft.x
            geometry.top = topLeft.y
            geometry.width = coordinates.size.width.toFloat()
            geometry.height = coordinates.size.height.toFloat()
        }
        .drawBehind {
            if (!live) {
                drawRect(base)
                return@drawBehind
            }
            // Outside a host the item is its own frame of reference.
            val host = frame ?: geometry
            val band = sweepLength(host.width, host.height, direction) * bandFraction
            val bandStart = bandStartAlongSweep(
                progress = progress.value,
                hostTopLeft = Offset(host.left, host.top),
                hostSize = Size(host.width, host.height),
                direction = direction,
                band = band,
            )
            val (start, end) = bandEndsInItem(bandStart, band, direction, itemTopLeft = Offset(geometry.left, geometry.top))
            drawRect(Brush.linearGradient(listOf(base, highlight, base), start = start, end = end))
        }
}

/** The unit vector the band travels along for [angle] degrees, in window coordinates (y points down). */
internal fun sweepDirection(angle: Float): Offset {
    val radians = angle.toDouble() * PI / 180.0
    return Offset(cos(radians).toFloat(), sin(radians).toFloat())
}

/**
 * How far a [width] by [height] host extends along [direction]: its projection
 * on that axis. The sweep runs across this length.
 */
internal fun sweepLength(width: Float, height: Float, direction: Offset): Float = abs(width * direction.x) + abs(height * direction.y)

/**
 * Where the band's near edge sits along [direction], in window coordinates
 * (the same units as a projection onto [direction]). The sweep starts at the
 * host's corner nearest its start and runs to the opposite one, so the band
 * starts and ends wholly off the host at every angle.
 */
internal fun bandStartAlongSweep(
    progress: Float,
    hostTopLeft: Offset,
    hostSize: Size,
    direction: Offset,
    band: Float,
): Float {
    val topLeftProjection = hostTopLeft.x * direction.x + hostTopLeft.y * direction.y
    // Going right or down, the nearest corner is the top-left one; a negative component moves it to the far side.
    val nearCorner = topLeftProjection + minOf(0f, hostSize.width * direction.x) + minOf(0f, hostSize.height * direction.y)
    return nearCorner + shimmerBandStart(progress, sweepLength(hostSize.width, hostSize.height, direction), band)
}

/**
 * Where the band's two gradient points sit in an item's own coordinates. Both
 * lie on the sweep line, so colour is constant across the line perpendicular
 * to [direction]: two items sharing a host draw the band's edges at the same
 * window position.
 */
internal fun bandEndsInItem(bandStart: Float, band: Float, direction: Offset, itemTopLeft: Offset): Pair<Offset, Offset> {
    val start = direction * bandStart - itemTopLeft
    val end = direction * (bandStart + band) - itemTopLeft
    return start to end
}

/**
 * Where the band's left edge sits at [progress] (0..1) of a sweep across a
 * host [width] wide: from `-band`, wholly left of it, to [width], wholly right
 * of it, at constant speed. The sweep starts and ends fully off the host, so a
 * cycle restarts on a flat surface and the restart does not show.
 */
internal fun shimmerBandStart(progress: Float, width: Float, band: Float): Float = -band + progress * (width + band)
