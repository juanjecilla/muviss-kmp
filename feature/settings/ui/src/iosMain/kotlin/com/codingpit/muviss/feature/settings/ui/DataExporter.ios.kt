package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

/**
 * Writes the export to a temp file and hands it to the system share sheet
 * (`UIActivityViewController`) — same "let the OS pick a destination" as
 * the Android `ACTION_SEND` chooser, but sharing a real file (so the
 * receiving app/AirDrop/Files sees a proper `.json` with [fileName]) rather
 * than plain text.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosDataExporter : DataExporter {
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
        val activityController = UIActivityViewController(
            activityItems = listOf(fileUrl),
            applicationActivities = null,
        )

        // No `popoverPresentationController` anchoring here: this Kotlin/Native
        // UIKit klib doesn't expose that property on `UIViewController`, and
        // Muviss ships iPhone + iPad ("1,2" in Info.plist) — on iPad, UIKit
        // needs a `sourceView`/`sourceRect` or `barButtonItem` set on it before
        // presenting a popover-style controller, or it throws at runtime. Until
        // that property is reachable, presenting still works fine on iPhone;
        // revisit if iPad crashes on this flow.
        val rootViewController = UIApplication.sharedApplication.keyWindow?.rootViewController
        rootViewController?.presentViewController(activityController, animated = true, completion = null)
    }
}

@Composable
actual fun rememberDataExporter(): DataExporter = remember { IosDataExporter() }
