package com.codingpit.muviss.core.designsystem.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard

/**
 * `UIActivityViewController` and the general pasteboard.
 *
 * Presented without a popover anchor, like `DataExporter.ios.kt` — the
 * Kotlin/Native UIKit klib does not expose `popoverPresentationController`, so
 * iPad carries the same open risk recorded there.
 */
private class IosTextSharer : TextSharer {
    override val canShare: Boolean = true

    override fun copy(text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    override fun share(text: String) {
        val controller = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        UIApplication.sharedApplication.keyWindow?.rootViewController
            ?.presentViewController(controller, animated = true, completion = null)
    }
}

@Composable
actual fun rememberTextSharer(): TextSharer = remember { IosTextSharer() }
