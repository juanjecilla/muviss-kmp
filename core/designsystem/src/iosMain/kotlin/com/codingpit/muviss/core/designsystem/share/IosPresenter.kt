package com.codingpit.muviss.core.designsystem.share

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

/**
 * The view controller the user is looking at: the key window's root, walked down
 * through every modal already presented on top of it. Presenting from the root
 * while a sheet is up fails with "already presenting", which is why the walk exists.
 */
fun topViewController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

/**
 * Presents a share sheet from [topViewController], anchored so iPad accepts it.
 *
 * On iPad a `UIActivityViewController` is a popover, and UIKit throws at present
 * time unless its `popoverPresentationController` has a source. Centred over the
 * presenting view with no arrow, the way an iPad shows a sheet with no button to
 * point at. The anchor is set on every device; iPhone ignores it.
 *
 * Returns false when there is no window to present from.
 */
@OptIn(ExperimentalForeignApi::class)
fun presentShareSheet(controller: UIActivityViewController): Boolean {
    val presenter = topViewController() ?: return false
    controller.popoverPresentationController?.let { popover ->
        popover.sourceView = presenter.view
        popover.sourceRect = presenter.view.bounds.useContents {
            CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0)
        }
        popover.permittedArrowDirections = 0u
    }
    presenter.presentViewController(controller, animated = true, completion = null)
    return true
}
