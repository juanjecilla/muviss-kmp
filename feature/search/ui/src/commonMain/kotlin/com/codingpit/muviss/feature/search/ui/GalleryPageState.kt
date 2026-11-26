package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.geometry.Size

/** The picture a gallery page draws. [Nothing] is an empty page: no picture has loaded to draw yet, or none can. */
internal enum class ShownImage { Replacement, Tapped, Nothing }

/**
 * What a gallery page draws, as a function of how its two loads stand.
 *
 * A page that replaces the tapped image draws the replacement once the replacement has loaded. Until then it draws the
 * tapped image once that has loaded, which is what keeps the picture on screen while the larger copy loads, and what
 * stays if that copy fails. Before the tapped image has loaded it draws nothing. A page that does not replace the tapped
 * image has nothing to fall back to, so it draws its own picture or nothing. A failed load never changes the answer on
 * its own; it only means the other load is what stays.
 */
internal fun shownImage(replacesSeed: Boolean, replacementLoaded: Boolean, tappedLoaded: Boolean): ShownImage = when {
    replacementLoaded -> ShownImage.Replacement
    replacesSeed && tappedLoaded -> ShownImage.Tapped
    else -> ShownImage.Nothing
}

/**
 * The size of the picture the page draws, which Telephoto's bounds are computed from. Null when nothing is drawn, or the
 * drawn picture has not been measured yet. [replacementSize] and [tappedSize] are the sizes of each load, known once that
 * load has succeeded.
 */
internal fun shownSize(shown: ShownImage, replacementSize: Size?, tappedSize: Size?): Size? = when (shown) {
    ShownImage.Replacement -> replacementSize
    ShownImage.Tapped -> tappedSize
    ShownImage.Nothing -> null
}

/** The test tag of the picture a gallery page draws, so a test can tell which of the two loads is on screen. */
internal fun imageViewerImageTag(shown: ShownImage): String = "imageViewerImage.${shown.name}"
