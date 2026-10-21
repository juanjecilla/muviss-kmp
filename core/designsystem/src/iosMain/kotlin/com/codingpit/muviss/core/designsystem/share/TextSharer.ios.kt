package com.codingpit.muviss.core.designsystem.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController

/** `UIActivityViewController` and the general pasteboard, presented via [presentShareSheet] (anchored for iPad). */
private class IosTextSharer(private val host: UIViewController) : TextSharer {
    override val canShare: Boolean = true

    override fun copy(text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    override fun share(text: String) {
        presentShareSheet(UIActivityViewController(activityItems = listOf(text), applicationActivities = null), host)
    }
}

@Composable
actual fun rememberTextSharer(): TextSharer {
    val host = LocalUIViewController.current
    return remember(host) { IosTextSharer(host) }
}
