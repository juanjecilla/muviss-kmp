package com.codingpit.muviss.core.designsystem.layout

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun LightStatusBarIcons() {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            val previous = controller?.systemBarsAppearance ?: 0
            val wasLight = previous and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            controller?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            onDispose { controller?.setSystemBarsAppearance(wasLight, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) }
        } else {
            @Suppress("DEPRECATION")
            val previous = decor.systemUiVisibility
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = previous and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            @Suppress("DEPRECATION")
            onDispose { decor.systemUiVisibility = previous }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
