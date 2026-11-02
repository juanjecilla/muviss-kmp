package com.codingpit.muviss.core.designsystem.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIPasteboard

/** `UIActivityViewController` and the general pasteboard, presented via [presentShareSheet] (anchored for iPad). */
private class IosTextSharer : TextSharer {
    override val canShare: Boolean = true

    override fun copy(text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    override fun share(text: String) {
        presentShareSheet(UIActivityViewController(activityItems = listOf(text), applicationActivities = null))
    }
}

@Composable
actual fun rememberTextSharer(): TextSharer = remember { IosTextSharer() }
