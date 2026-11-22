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
    val fromForeground = foregroundWindowScene()?.let { scene ->
        val windows = scene.windows.filterIsInstance<UIWindow>()
        scene.keyWindow ?: windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull()
    }
    return fromForeground
        ?: app.keyWindow
        ?: app.connectedScenes.asSequence().filterIsInstance<UIWindowScene>().flatMap { it.windows.filterIsInstance<UIWindow>() }.firstOrNull()
}

/**
 * The foreground-active window scene, or null before any scene is foregrounded. The one place the
 * app resolves "the scene the person is looking at" (#468); the store review sheet asks for it too (#410).
 */
fun foregroundWindowScene(): UIWindowScene? = UIApplication.sharedApplication.connectedScenes
    .asSequence()
    .filterIsInstance<UIWindowScene>()
    .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }

/**
 * The view controller the user is looking at: the root of [host]'s window, walked down
 * through every modal already presented on top of it. Presenting from the root
 * while a sheet is up fails with "already presenting", which is why the walk exists.
 *
 * [host] is the controller hosting the Compose UI that asked (`LocalUIViewController`).
 * Its window is the scene the user tapped in, which on an iPad running two Muviss
 * windows side by side no scene lookup can tell apart, since both are foreground-active.
 * Without one (or before it is in a window) the guess in [currentWindow] is the fallback.
 */
fun topViewController(host: UIViewController? = null): UIViewController? {
    var top = (host?.viewIfLoaded?.window ?: currentWindow())?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

/**
 * Presents a share sheet from [topViewController] of [host], anchored so iPad accepts it.
 *
 * On iPad a `UIActivityViewController` is a popover, and UIKit throws at present
 * time unless its `popoverPresentationController` has a source. Centred over the
 * presenting view with no arrow, the way an iPad shows a sheet with no button to
 * point at. The anchor is set on every device; iPhone ignores it.
 *
 * Returns false when there is no window to present from.
 */
@OptIn(ExperimentalForeignApi::class)
fun presentShareSheet(controller: UIActivityViewController, host: UIViewController? = null): Boolean {
    val presenter = topViewController(host) ?: return false
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
