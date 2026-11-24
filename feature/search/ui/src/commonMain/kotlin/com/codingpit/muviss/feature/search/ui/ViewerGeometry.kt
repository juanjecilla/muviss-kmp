package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.LayoutDirection
import me.saket.telephoto.zoomable.ZoomableContentLocation

/**
 * Where the Image Viewer draws an image of [intrinsic] size inside its viewport: [ContentScale.Fit], scaled up or down
 * to fill the viewport and centred. Telephoto needs this to match the pixels on screen, because its pan and zoom
 * limits come from the content's bounds. Telephoto's own `scaledInsideAndCenterAligned` is `ContentScale.Inside`, which
 * never upscales, so a small still would report bounds smaller than the picture drawn over them.
 */
internal class FitContentLocation(private val intrinsic: Size) : ZoomableContentLocation {

    @Suppress("OVERRIDE_DEPRECATION") // Telephoto deprecates `size` in favour of `location`; it still has to be implemented.
    override fun size(layoutSize: Size): Size {
        // The very function Coil's painter uses, so the reported bounds cannot disagree with the drawing.
        val factor = ContentScale.Fit.computeScaleFactor(intrinsic, layoutSize)
        return Size(intrinsic.width * factor.scaleX, intrinsic.height * factor.scaleY)
    }

    override fun location(layoutSize: Size, direction: LayoutDirection): Rect {
        val drawn = size(layoutSize)
        val topLeft = Offset((layoutSize.width - drawn.width) / 2f, (layoutSize.height - drawn.height) / 2f)
        return Rect(topLeft, drawn)
    }
}

/**
 * The content location for a picture whose size is known, or Telephoto's unspecified location until it loads.
 * `Size.Unspecified` is NaN on both axes, so the `> 0` test rejects it along with null and empty pictures.
 */
internal fun fitLocation(intrinsic: Size?): ZoomableContentLocation = intrinsic?.takeIf { it.width > 0f && it.height > 0f }?.let { FitContentLocation(it) }
    ?: ZoomableContentLocation.scaledInsideAndCenterAligned(null)
