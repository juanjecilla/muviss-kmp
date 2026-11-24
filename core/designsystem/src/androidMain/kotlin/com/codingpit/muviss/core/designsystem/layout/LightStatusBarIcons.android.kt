package com.codingpit.muviss.core.designsystem.layout

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/**
 * The live light-icon requests on one window, and the dark-icon appearance the first of them found.
 *
 * Requests overlap: a navigation crossfade starts the incoming screen's request before the outgoing one is
 * disposed. A plain save-and-restore would let the outgoing screen put back the appearance the incoming one has
 * just cleared, so the first request saves and only the last one restores.
 */
private class LightIconRequests {
    var count = 0
    var savedDarkIcons = false
}

/** Main thread only, like every other composition-side access. */
private val requestsByWindow = WeakHashMap<Window, LightIconRequests>()

@Composable
actual fun LightStatusBarIcons() {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        val requests = requestsByWindow.getOrPut(window) { LightIconRequests() }
        if (requests.count++ == 0) requests.savedDarkIcons = setDarkStatusIcons(window, dark = false)
        onDispose {
            if (--requests.count == 0) {
                setDarkStatusIcons(window, dark = requests.savedDarkIcons)
                requestsByWindow.remove(window)
            }
        }
    }
}

/**
 * Sets whether the status bar draws dark icons, returning what it drew before. The pre-R branch uses the
 * deprecated flag word, which is the only way to reach the status bar on those releases.
 */
@Suppress("DEPRECATION")
private fun setDarkStatusIcons(window: Window, dark: Boolean): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val controller = window.insetsController ?: return false
        val previous = (controller.systemBarsAppearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != 0
        controller.setSystemBarsAppearance(
            if (dark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
        return previous
    }
    @Suppress("DEPRECATION")
    val decor = window.decorView

    @Suppress("DEPRECATION")
    val flags = decor.systemUiVisibility
    val previous = (flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0
    @Suppress("DEPRECATION")
    decor.systemUiVisibility = if (dark) {
        flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    } else {
        flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
    }
    return previous
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
