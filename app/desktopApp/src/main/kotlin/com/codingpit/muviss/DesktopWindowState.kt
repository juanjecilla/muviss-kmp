package com.codingpit.muviss

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.util.prefs.Preferences

/**
 * Persists the desktop window's size/position across restarts.
 *
 * EPIC 12 considered piggy-backing on the shared `appSettings` SQLDelight
 * table (`SettingsRepository`) for this, but rejected it: that table is
 * `commonMain` schema shared by Android/iOS/JVM/JS/Wasm (ADR 0004), so
 * adding columns nobody but the desktop target ever reads or writes would
 * need a `.sqm` migration + updated verification fixture per ADR 0008 for a
 * value that is, by definition, desktop-window-manager-specific — Android
 * and iOS already get this behavior for free from the OS, and web has no
 * native window to persist. `java.util.prefs.Preferences` is a JVM-only,
 * per-user, schema-less key/value store built for exactly this — it keeps
 * the concern entirely inside `:app:desktopApp` and touches nothing shared.
 * Documented in `docs/RELEASING.md`.
 */
private val windowStatePrefs: Preferences =
    Preferences.userRoot().node("com/codingpit/muviss/desktop/window")

private const val KEY_WIDTH = "width"
private const val KEY_HEIGHT = "height"
private const val KEY_X = "x"
private const val KEY_Y = "y"

/** Sensible default for a first-ever launch, before anything is persisted. */
internal val DEFAULT_WINDOW_SIZE = DpSize(1100.dp, 720.dp)

/** Floor so the window can never be resized into unusable, content-less territory. */
internal val MIN_WINDOW_SIZE = DpSize(480.dp, 360.dp)

/**
 * Builds the initial [WindowState] from whatever was last persisted, falling
 * back to [DEFAULT_WINDOW_SIZE] / the platform's default placement when this
 * is the first launch (or the prefs node is empty/corrupt).
 */
internal fun restoredWindowState(): WindowState {
    val width = windowStatePrefs.getFloat(KEY_WIDTH, DEFAULT_WINDOW_SIZE.width.value)
    val height = windowStatePrefs.getFloat(KEY_HEIGHT, DEFAULT_WINDOW_SIZE.height.value)
    val hasPersistedPosition =
        windowStatePrefs.get(KEY_X, null) != null && windowStatePrefs.get(KEY_Y, null) != null
    val position =
        if (hasPersistedPosition) {
            WindowPosition(
                windowStatePrefs.getFloat(KEY_X, 0f).dp,
                windowStatePrefs.getFloat(KEY_Y, 0f).dp,
            )
        } else {
            WindowPosition.PlatformDefault
        }
    return WindowState(size = DpSize(width.dp, height.dp), position = position)
}

/** Call before the window disappears (close/minimize-to-quit) to save its current bounds. */
internal fun persistWindowState(state: WindowState) {
    windowStatePrefs.putFloat(KEY_WIDTH, state.size.width.value)
    windowStatePrefs.putFloat(KEY_HEIGHT, state.size.height.value)
    val position = state.position
    if (position is WindowPosition.Absolute) {
        windowStatePrefs.putFloat(KEY_X, position.x.value)
        windowStatePrefs.putFloat(KEY_Y, position.y.value)
    }
}
