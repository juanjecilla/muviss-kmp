package com.codingpit.muviss

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.awt.Dimension

fun main() = application {
    // See DesktopWindowState.kt for why this is java.util.prefs rather than
    // the shared appSettings table.
    val windowState = remember { restoredWindowState() }

    // Belt-and-suspenders: onCloseRequest below covers the normal quit path,
    // but this also captures drags/resizes if the process ever dies without
    // a clean close (force-quit, `kill`). `collectLatest` + a trailing delay
    // is a manual debounce (kotlinx.coroutines' own `Flow.debounce` is a
    // @FlowPreview API): each new emission cancels the previous 500ms wait,
    // so a drag/resize in progress doesn't hammer java.util.prefs every frame.
    LaunchedEffect(windowState) {
        snapshotFlow { windowState.size to windowState.position }
            .collectLatest {
                delay(500)
                persistWindowState(windowState)
            }
    }

    Window(
        onCloseRequest = {
            persistWindowState(windowState)
            exitApplication()
        },
        state = windowState,
        title = "Muviss",
    ) {
        // WindowScope.window is the underlying java.awt/Swing peer
        // (ComposeWindow extends JFrame) — minimumSize has no Compose-level
        // equivalent on the WindowState/Window API, so it's set here once.
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(MIN_WINDOW_SIZE.width.value.toInt(), MIN_WINDOW_SIZE.height.value.toInt())
        }
        MuvissApp()
    }
}
