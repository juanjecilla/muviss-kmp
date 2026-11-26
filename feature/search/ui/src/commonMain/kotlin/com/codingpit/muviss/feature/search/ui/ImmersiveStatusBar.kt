package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.layout.LightStatusBarIcons

/**
 * How far the band fades out below the status bar. The bar itself is a solid plateau; only this stretch below it is a
 * gradient, and it is short so the first rows of the body are darkened as little as possible.
 */
internal val ImmersiveBarFade = 56.dp

/** Test tag of the dark band, so a test can assert it is present exactly while artwork covers the bar. */
internal const val IMMERSIVE_BAR_TAG = "immersiveBar"

/**
 * Whether artwork covers any of the status bar, that is `[0, topInsetPx)` in window coordinates. The artwork's edges are
 * the ones it was laid out at (top may be negative once scrolled up), so the test is an interval intersection. With no
 * bar (inset 0, a hidden status bar, or a desktop window) nothing can cover it.
 *
 * The rule is "touches", not "fully covers": at the offset where the artwork's bottom edge is still a few pixels into the
 * bar, the bar's lower part is over artwork that may be dark (an episode still has no fade to the background), so the
 * band must still be there. The icons come back only once the artwork has cleared the bar entirely.
 */
internal fun artworkCoversBar(artworkTopPx: Float, artworkBottomPx: Float, topInsetPx: Float): Boolean = topInsetPx > 0f && artworkTopPx < topInsetPx && artworkBottomPx > 0f

/** One artwork's edges in window coordinates, as the last layout pass placed it. */
internal data class ArtworkEdges(val top: Float, val bottom: Float)

/**
 * The hero artwork of one immersive screen, as the layout measured it. Measured rather than derived from a scroll offset
 * because the artwork is not one rectangle: on a phone the poster overhangs the backdrop by 30 dp and on a tablet it sits
 * 56 dp up inside the body, so a model based on the backdrop's height would release the bar while a poster still
 * covers it. Artwork that scrolls out of composition (the hero item once the list has moved on) is forgotten.
 */
internal class ImmersiveArtwork {
    private val edges = mutableStateMapOf<Any, ArtworkEdges>()

    fun report(key: Any, top: Float, bottom: Float) {
        val next = ArtworkEdges(top, bottom)
        // Written only on a change: layout reports every frame of a scroll, and an unchanged write would still invalidate.
        if (edges[key] != next) edges[key] = next
    }

    fun forget(key: Any) {
        edges.remove(key)
    }

    /** True while any tracked artwork touches the bar. Reads the state map, so a reader recomposes when it flips. */
    fun coversBar(topInsetPx: Float): Boolean = edges.values.any { artworkCoversBar(it.top, it.bottom, topInsetPx) }
}

/** The screen's [ImmersiveArtwork]. Null outside an immersive screen, where [trackImmersiveArtwork] does nothing. */
internal val LocalImmersiveArtwork = compositionLocalOf<ImmersiveArtwork?> { null }

/** Reports this node's window-coordinate edges to the screen's [ImmersiveArtwork] for as long as it is composed. */
@Composable
internal fun Modifier.trackImmersiveArtwork(): Modifier {
    val artwork = LocalImmersiveArtwork.current ?: return this
    val key = remember(artwork) { Any() }
    DisposableEffect(artwork, key) {
        onDispose { artwork.forget(key) }
    }
    return onGloballyPositioned { coordinates ->
        val top = coordinates.positionInRoot().y
        artwork.report(key, top, top + coordinates.size.height)
    }
}

/**
 * The status bar's treatment on an immersive screen: while the artwork covers the bar, light icons and one fixed dark
 * band over exactly the bar; otherwise nothing, and the theme's icons show. Icons and band read the same
 * [ImmersiveArtwork.coversBar] result, so the two cannot disagree. The band is fixed to the top of the screen rather than
 * scrolling with the artwork, which keeps it over the bar at every offset the icons are requested for.
 *
 * [topInset] is a parameter for the same reason [immersiveTopPadding] is read here: Skiko reports zero insets, so tests
 * pass a synthetic one. It is keyed into the derived state, so a rotation or an inset change recomputes at once.
 *
 * Loading and error draw no artwork, so they request nothing and keep the theme's icons.
 */
@Composable
internal fun ImmersiveStatusBar(artwork: ImmersiveArtwork, topInset: Dp, modifier: Modifier = Modifier) {
    val topInsetPx = with(LocalDensity.current) { topInset.toPx() }
    val covered by remember(artwork, topInsetPx) { derivedStateOf { artwork.coversBar(topInsetPx) } }
    if (!covered) return
    LightStatusBarIcons()
    val total = topInset + ImmersiveBarFade
    val dark = Color.Black.copy(alpha = 0.45f)
    Box(
        modifier
            .fillMaxWidth()
            .height(total)
            .testTag(IMMERSIVE_BAR_TAG)
            .background(Brush.verticalGradient(0f to dark, topInset / total to dark, 1f to Color.Transparent)),
    )
}
