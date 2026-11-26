package com.codingpit.muviss.core.designsystem.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.Foundation.NSNumber
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.UIKit.UIStatusBarStyle
import platform.UIKit.UIStatusBarStyleDefault
import platform.UIKit.UIStatusBarStyleLightContent

/**
 * The live light-icon requests, and the style the first of them found. Requests overlap (a navigation crossfade starts
 * the incoming screen's request before the outgoing one is disposed), so the first request saves and only the last one
 * restores: the outgoing screen must not put back the style the incoming one has just set.
 *
 * iOS has one window in this app, so one counter is enough. Main thread only, like every composition-side access.
 *
 * `UIApplication.statusBarStyle` only takes effect while `UIViewControllerBasedStatusBarAppearance` is NO in Info.plist;
 * with the default YES, UIKit asks the view controller instead and the value set here is ignored.
 */
private var lightRequests = 0
private var savedStyle: UIStatusBarStyle = UIStatusBarStyleDefault

@Composable
actual fun LightStatusBarIcons() {
    DisposableEffect(Unit) {
        if (lightRequests++ == 0) savedStyle = currentStatusBarStyle()
        setStatusBarStyle(UIStatusBarStyleLightContent)
        onDispose {
            if (--lightRequests == 0) setStatusBarStyle(savedStyle)
        }
    }
}

// The application-level status bar style is deprecated (in favour of the view controller's), but it is the one UIKit
// applies while view-controller-based appearance is off, which this app sets (Info.plist). Read and written as a
// property: the SDK exposes no animated setter to Kotlin under this name, and the property is what the header documents.
@Suppress("DEPRECATION")
private fun currentStatusBarStyle(): UIStatusBarStyle = UIApplication.sharedApplication.statusBarStyle

@Suppress("DEPRECATION")
private fun setStatusBarStyle(style: UIStatusBarStyle) {
    // The Kotlin binding exposes the property read-only (its setter is deprecated since iOS 9, and this target's
    // binding hides it), so the public read-write property is set through key-value coding.
    UIApplication.sharedApplication.setValue(NSNumber(integer = style), forKey = "statusBarStyle")
}
