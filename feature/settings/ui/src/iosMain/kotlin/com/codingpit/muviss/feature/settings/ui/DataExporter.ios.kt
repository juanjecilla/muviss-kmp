package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import com.codingpit.muviss.core.designsystem.share.presentShareSheet
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIViewController

/**
 * Writes the export to a temp file and hands it to the system share sheet
 * (`UIActivityViewController`) — same "let the OS pick a destination" as
 * the Android `ACTION_SEND` chooser, but sharing a real file (so the
 * receiving app/AirDrop/Files sees a proper `.json` with [fileName]) rather
 * than plain text.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosDataExporter(private val host: UIViewController) : DataExporter {
    override fun export(json: String, fileName: String) {
        // `NSString` is a distinct Kotlin/Native declaration from `kotlin.String`
        // even though they're ABI-compatible on Darwin, so these `NSString`
        // extension functions need an explicit (always-succeeding) cast —
        // the compiler's "can never succeed" warning is about the cast being
        // redundant at the ABI level, not about it failing at runtime.
        @Suppress("CAST_NEVER_SUCCEEDS")
        val filePath = (NSTemporaryDirectory() as NSString).stringByAppendingPathComponent(fileName)

        @Suppress("CAST_NEVER_SUCCEEDS")
        val written = (json as NSString).writeToFile(
            filePath,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        if (!written) return

        val fileUrl = NSURL.fileURLWithPath(filePath)
        // Anchored for iPad and presented from the top controller by the shared presenter.
        presentShareSheet(UIActivityViewController(activityItems = listOf(fileUrl), applicationActivities = null), host)
    }
}

@Composable
actual fun rememberDataExporter(): DataExporter {
    val host = LocalUIViewController.current
    return remember(host) { IosDataExporter(host) }
}
