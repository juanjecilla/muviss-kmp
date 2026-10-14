package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeCommaSeparatedText
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.UniformTypeIdentifiers.UTTypePlainText
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * `UIDocumentPickerViewController` over the formats the importers read: JSON
 * (Trakt, and Muviss's own backups) and CSV (TV Time, Muviss's generic format).
 * It used to be a stub returning null behind a live Import row (EPIC 29, #72).
 *
 * `asCopy = true` hands back a copy in the app's sandbox, so the read needs no
 * security-scoped access. The picker holds its delegate weakly, which is why
 * [delegate] keeps it alive until it answers.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosFileImporter : FileImporter {

    private var delegate: PickerDelegate? = null

    override suspend fun pickFile(): PickedFile? = suspendCancellableCoroutine { continuation ->
        val presenter = topViewController()
        if (presenter == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypeJSON, UTTypeCommaSeparatedText, UTTypePlainText),
            asCopy = true,
        )
        val answer = PickerDelegate { url ->
            delegate = null
            if (continuation.isActive) continuation.resume(url?.let(::read))
        }
        delegate = answer
        picker.delegate = answer
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    private fun read(url: NSURL): PickedFile? {
        val content = NSString.create(contentsOfURL = url, encoding = NSUTF8StringEncoding, error = null)?.toString() ?: return null
        return PickedFile(fileName = url.lastPathComponent ?: "import", content = content)
    }
}

private class PickerDelegate(private val onResult: (NSURL?) -> Unit) :
    NSObject(),
    UIDocumentPickerDelegateProtocol {

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onResult(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onResult(null)
    }
}

/** The controller on top, so the picker presents over a sheet or dialog rather than failing behind it. */
internal fun topViewController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

@Composable
actual fun rememberFileImporter(): FileImporter = remember { IosFileImporter() }
