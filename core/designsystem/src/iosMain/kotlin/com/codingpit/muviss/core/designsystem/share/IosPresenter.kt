package com.codingpit.muviss.core.designsystem.share

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

/**
 * The window the user is looking at. `UIApplication.keyWindow` is deprecated and can be
 * nil or belong to a background scene on a multi-scene iPad, so the foreground-active
 * `UIWindowScene` is asked first, for its key window or else its first one (the
 * application-level key window may belong to another scene); the application-level key
 * window and then any window are fallbacks for the cases where no scene is foregrounded yet.
 */
@Suppress("DEPRECATION")
private fun currentWindow(): UIWindow? {
    val app = UIApplication.sharedApplication
    val scenes = app.connectedScenes.filterIsInstance<UIWindowScene>()
    val foreground = scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
    val fromForeground = foreground?.let { scene ->
        val windows = scene.windows.filterIsInstance<UIWindow>()
        scene.keyWindow ?: windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull()
    }
    return fromForeground
        ?: app.keyWindow
        ?: scenes.asSequence().flatMap { it.windows.filterIsInstance<UIWindow>() }.firstOrNull()
}

/**
 * The view controller the user is looking at: the current window's root, walked down
 * through every modal already presented on top of it. Presenting from the root
 * while a sheet is up fails with "already presenting", which is why the walk exists.
 */
fun topViewController(): UIViewController? {
    var top = currentWindow()?.rootViewController
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
