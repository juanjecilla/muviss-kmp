package com.codingpit.muviss.core.designsystem.share

import androidx.compose.runtime.Composable

/**
 * Hands a piece of text to the platform: onto the clipboard, or into the
 * system share sheet.
 *
 * One seam for both because Compose's common `Clipboard` has no portable way
 * to build a plain-text `ClipEntry` — each platform constructs its own — so
 * copying needs an actual per platform anyway, the same as sharing does.
 *
 * Desktop has no share sheet and some browsers no `navigator.share`; there
 * [canShare] is false and a caller offers [copy] alone rather than a button
 * that silently does nothing.
 */
interface TextSharer {
    val canShare: Boolean

    /**
     * True where the OS already confirms a copy itself (Android 13+ shows its
     * own clipboard overlay), so a caller does not say it twice.
     */
    val confirmsCopy: Boolean get() = false

    fun copy(text: String)

    fun share(text: String)
}

/** The platform's [TextSharer]. */
@Composable
expect fun rememberTextSharer(): TextSharer
