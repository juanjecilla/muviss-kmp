package com.codingpit.shimmer

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Paints a placeholder with a moving highlight: `base`, `highlight`, `base`
 * across a band that sweeps left to right, or right to left under an RTL layout.
 *
 * Inside a [ShimmerHost] the band is the host's, so neighbours line up. Outside
 * one the placeholder sweeps across its own width, which is the old per-item
 * behaviour. [animate] false, or a host with `animate = false`, draws `base`
 * as a flat fill.
 */
fun Modifier.shimmer(base: Color, highlight: Color, animate: Boolean = true): Modifier = composed {
    val hosted = LocalShimmerWave.current
    val ownProgress: State<Float>? = if (hosted == null && animate) rememberSweep(DEFAULT_SHIMMER_DURATION_MILLIS) else null
    val still = remember { mutableFloatStateOf(0f) }
    val progress: State<Float> = hosted?.progress ?: ownProgress ?: still
    val live = animate && (hosted?.animate ?: true)
    val bandFraction = hosted?.bandFraction ?: DEFAULT_SHIMMER_BAND_FRACTION
    val geometry = remember { ShimmerGeometry() }
    val frame = hosted?.geometry
    // A host decides the direction for all of its placeholders, so the band stays one band across them.
    val mirrored = hosted?.rtl ?: (LocalLayoutDirection.current == LayoutDirection.Rtl)

    Modifier
        .onGloballyPositioned { coordinates ->
            geometry.left = coordinates.positionInRoot().x
            geometry.width = coordinates.size.width.toFloat()
        }
        .drawBehind {
            if (!live) {
                drawRect(base)
                return@drawBehind
            }
            // Outside a host the item is its own frame of reference.
            val hostLeft = frame?.left ?: geometry.left
            val hostWidth = frame?.width ?: geometry.width
            val band = hostWidth * bandFraction
            val bandStart = bandStartInItem(shimmerBandStart(progress.value, hostWidth, band, mirrored), hostLeft, geometry.left)
            drawRect(Brush.horizontalGradient(listOf(base, highlight, base), startX = bandStart, endX = bandStart + band))
        }
}

/**
 * Where the band's left edge sits in an item's own coordinates. [sweepStart] is
 * the edge within its host (see [shimmerBandStart]); the host's window position
 * is added and the item's is taken away. The band is computed in window
 * coordinates and then shifted into the item, so two items at different offsets
 * draw the same band at the same place on screen.
 */
internal fun bandStartInItem(sweepStart: Float, hostLeft: Float, itemLeft: Float): Float = hostLeft + sweepStart - itemLeft

/**
 * Where the band's left edge sits at [progress] (0..1) of a sweep across a
 * host [width] wide: from `-band`, wholly left of it, to [width], wholly right
 * of it, at constant speed. The sweep starts and ends fully off the host, so a
 * cycle restarts on a flat surface and the restart does not show.
 *
 * [mirrored] (RTL) is the same sweep reflected across the host's centre, so the
 * band starts wholly right of the host ([width]) and ends wholly left of it
 * (`-band`), travelling right to left. For any progress, the LTR and RTL band
 * edges sum to `width - band`, so they are mirror images of each other.
 */
internal fun shimmerBandStart(progress: Float, width: Float, band: Float, mirrored: Boolean = false): Float = if (mirrored) width - progress * (width + band) else -band + progress * (width + band)
