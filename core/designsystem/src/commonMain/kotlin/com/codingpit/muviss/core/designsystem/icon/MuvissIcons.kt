package com.codingpit.muviss.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The five bottom-nav glyphs, hand-bundled as [ImageVector]s because
 * `material-icons-extended` doesn't publish for this Compose Multiplatform
 * version (see CLAUDE.md's setup gotchas). Each is the standard Material
 * Design "filled" 24dp glyph, parsed from its SVG path data with
 * [PathParser] — the same technique the real `material-icons-extended`
 * artifact's generated sources use, just written by hand for exactly the
 * five icons this app needs instead of pulling in the whole set.
 *
 * Built lazily and cached per icon (mirroring the generated icon pattern) so
 * [PathParser] only runs once per glyph no matter how many times the getter
 * is read (e.g. on every recomposition of the bottom nav).
 */
object MuvissIcons {

    val Search: ImageVector get() = cached("Search", SEARCH_PATH)
    val Library: ImageVector get() = cached("Library", LIBRARY_PATH)
    val WatchNext: ImageVector get() = cached("WatchNext", WATCH_NEXT_PATH)
    val Profile: ImageVector get() = cached("Profile", PROFILE_PATH)
    val Settings: ImageVector get() = cached("Settings", SETTINGS_PATH)

    private val cache = mutableMapOf<String, ImageVector>()

    private fun cached(name: String, pathData: String): ImageVector = cache.getOrPut(name) { buildIcon(name, pathData) }

    private fun buildIcon(name: String, pathData: String): ImageVector = ImageVector
        .Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
        .build()

    // Material Design "Search" (filled, 24dp).
    private const val SEARCH_PATH =
        "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59,16,11.11,16,9.5C16,5.91,13.09,3,9.5,3S3,5.91,3,9.5S5.91,16,9.5,16c1.61,0,3.09,-0.59,4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14,5,11.99,5,9.5S7.01,5,9.5,5S14,7.01,14,9.5S11.99,14,9.5,14z"

    // Material Design "Bookmark" (filled, 24dp) — the saved-library tab.
    private const val LIBRARY_PATH =
        "M17,3H7C5.9,3,5,3.9,5,5v16l7,-3l7,3V5C19,3.9,18.1,3,17,3z"

    // Material Design "PlayCircle" (filled, 24dp) — watch-next / progress.
    private const val WATCH_NEXT_PATH =
        "M12,2C6.48,2,2,6.48,2,12s4.48,10,10,10s10,-4.48,10,-10S17.52,2,12,2z M10,16.5v-9l6,4.5L10,16.5z"

    // Material Design "Person" (filled, 24dp).
    private const val PROFILE_PATH =
        "M12,12c2.21,0,4,-1.79,4,-4c0,-2.21,-1.79,-4,-4,-4S8,5.79,8,8C8,10.21,9.79,12,12,12z M12,14c-2.67,0,-8,1.34,-8,4v2h16v-2C20,15.34,14.67,14,12,14z"

    // Material Design "Settings" (filled gear, 24dp).
    private const val SETTINGS_PATH =
        "M19.14,12.94c0.04,-0.3,0.06,-0.61,0.06,-0.94c0,-0.32,-0.02,-0.64,-0.07,-0.94l2.03,-1.58c0.18,-0.14,0.23,-0.41,0.12,-0.61l-1.92,-3.32c-0.12,-0.22,-0.37,-0.29,-0.59,-0.22l-2.39,0.96c-0.5,-0.38,-1.03,-0.7,-1.62,-0.94L14.4,2.81c-0.04,-0.24,-0.24,-0.41,-0.48,-0.41h-3.84c-0.24,0,-0.43,0.17,-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22,-0.08,-0.47,0,-0.59,0.22L2.74,8.87c-0.12,0.21,-0.08,0.47,0.12,0.61l2.03,1.58C4.84,11.36,4.82,11.69,4.82,12s0.02,0.64,0.07,0.94l-2.03,1.58c-0.18,0.14,-0.23,0.41,-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39,-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44,-0.17,0.47,-0.41l0.36,-2.54c0.59,-0.24,1.13,-0.56,1.62,-0.94l2.39,0.96c0.22,0.08,0.47,0,0.59,-0.22l1.92,-3.32c0.12,-0.22,0.07,-0.47,-0.12,-0.61L19.14,12.94z M12,15.6c-1.98,0,-3.6,-1.62,-3.6,-3.6s1.62,-3.6,3.6,-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z"
}
