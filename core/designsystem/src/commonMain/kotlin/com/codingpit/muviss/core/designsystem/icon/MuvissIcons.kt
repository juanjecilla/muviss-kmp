package com.codingpit.muviss.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Muviss icon set, hand-bundled as [ImageVector]s because
 * `material-icons-extended` doesn't publish for this Compose Multiplatform
 * version (see CLAUDE.md's setup gotchas). Follows the design system's
 * drawing rules: 24×24dp grid, 2dp stroke with round caps and joins,
 * outlined style except the brand play triangle, the filled star/heart and
 * the filled check-circle "watched" state. Single color — tint from the M3
 * role at the call site; the baked-in black here is only the vector's
 * intrinsic color and is always overridden by `Icon(tint = …)`.
 *
 * Built lazily and cached per icon so [PathParser] only runs once per glyph
 * no matter how many times the getter is read (e.g. on every recomposition
 * of the nav bar).
 */
object MuvissIcons {

    // Navigation
    val Search: ImageVector get() = stroked("Search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14", "M20 20l-3.5-3.5")
    val Library: ImageVector
        get() = stroked(
            "Library",
            roundedRect(4f, 4f, 6f, 8f),
            roundedRect(14f, 4f, 6f, 8f),
            roundedRect(4f, 15f, 6f, 5f),
            roundedRect(14f, 15f, 6f, 5f),
        )
    val WatchNext: ImageVector get() = stroked("WatchNext", "M5 13l4 4L19 7")
    val Profile: ImageVector get() = stroked("Profile", "M12 4.6a3.4 3.4 0 1 0 0 6.8a3.4 3.4 0 1 0 0-6.8", "M5 20c0-3.5 3-5.5 7-5.5s7 2 7 5.5")
    val Settings: ImageVector
        get() = stroked(
            "Settings",
            "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6",
            "M12 3v3M12 18v3M3 12h3M18 12h3M6 6l2 2M16 16l2 2M18 6l-2 2M8 16l-2 2",
        )

    // Library actions
    val Favorite: ImageVector get() = filled("Favorite", HEART_PATH)
    val FavoriteOutline: ImageVector get() = stroked("FavoriteOutline", HEART_PATH)
    val Add: ImageVector get() = stroked("Add", "M12 5v14M5 12h14")
    val AddToList: ImageVector get() = stroked("AddToList", "M4 6h12M4 12h12M4 18h8", "M18 15v6M15 18h6")
    val Sort: ImageVector get() = stroked("Sort", "M4 6h16M7 12h10M10 18h4")
    val Filter: ImageVector get() = stroked("Filter", "M4 5h16l-6 7v5l-4 2v-7z")

    // Tracking
    val Check: ImageVector get() = stroked("Check", "M5 13l4 4L19 7")
    val CheckCircle: ImageVector get() = filled("CheckCircle", CHECK_CIRCLE_PATH)
    val Bell: ImageVector get() = stroked("Bell", "M12 3a5 5 0 0 1 5 5v4l2 3H5l2-3V8a5 5 0 0 1 5-5z", "M10 19.5a2 2 0 0 0 4 0")
    val BellOff: ImageVector get() = stroked("BellOff", "M12 3a5 5 0 0 1 5 5v4l2 3H9M5.7 9.5V12l-2 3h5", "M10 19.5a2 2 0 0 0 4 0", "M4 4l16 16")
    val Star: ImageVector get() = filled("Star", STAR_PATH)
    val StarOutline: ImageVector get() = stroked("StarOutline", STAR_PATH)

    // Detail / nav
    val Back: ImageVector get() = stroked("Back", "M15 5l-7 7 7 7")
    val Close: ImageVector get() = stroked("Close", "M6 6l12 12M18 6L6 18")
    val ChevronRight: ImageVector get() = stroked("ChevronRight", "M9 5l7 7-7 7")
    val ChevronDown: ImageVector get() = stroked("ChevronDown", "M5 9l7 7 7-7")

    // Personal note (EPIC 15): a pencil, matching Material's "edit".
    val Note: ImageVector get() = stroked("Note", "M4 20h4l10-10a2.8 2.8 0 0 0-4-4L4 16v4z", "M13.5 6.5l4 4")

    // Triage (ADR 0010) — the four verdicts plus undo
    val Skip: ImageVector get() = stroked("Skip", "M6 6l12 12M18 6L6 18")
    val Later: ImageVector get() = stroked("Later", "M7 4h10a1 1 0 0 1 1 1v15l-6-4-6 4V5a1 1 0 0 1 1-1z")
    val Watching: ImageVector get() = stroked("Watching", "M12 4.5C7 4.5 3.5 8.5 2.5 12c1 3.5 4.5 7.5 9.5 7.5s8.5-4 9.5-7.5c-1-3.5-4.5-7.5-9.5-7.5z", "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6")
    val CaughtUp: ImageVector get() = stroked("CaughtUp", "M3 13l4 4L15 7", "M13 15l2 2L21 7")
    val Undo: ImageVector get() = stroked("Undo", "M4 9h11a5 5 0 0 1 0 10h-6", "M4 9l4-4M4 9l4 4")

    // Data / sync
    val Sync: ImageVector get() = stroked("Sync", "M20 12a8 8 0 1 0 -3 6.2", "M20 12v-4M20 12h-4")
    val Import: ImageVector get() = stroked("Import", "M12 4v11M6 9.5l6 5.5 6-5.5", "M4 20h16")
    val Export: ImageVector get() = stroked("Export", "M12 15V4M6 9.5L12 4l6 5.5", "M4 20h16")
    val Account: ImageVector
        get() = stroked(
            "Account",
            "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18",
            "M12 7.5a3 3 0 1 0 0 6a3 3 0 1 0 0-6",
            "M6.5 18.5c1-2.5 3-3.5 5.5-3.5s4.5 1 5.5 3.5",
        )
    val Calendar: ImageVector get() = stroked("Calendar", roundedRect(4f, 5f, 16f, 16f), "M4 9h16M8 3v4M16 3v4")

    // States
    val Error: ImageVector get() = stroked("Error", "M12 3L2 20h20z", "M12 10v4M12 16.8v.2")
    val SearchOff: ImageVector get() = stroked("SearchOff", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14", "M20 20l-3.5-3.5", "M8.5 8.5l5 5M13.5 8.5l-5 5")
    val CloudOff: ImageVector get() = stroked("CloudOff", "M20 17.6A4.5 4.5 0 0 0 17.5 9h-1.8A7 7 0 0 0 9 4.7M4.6 6.5A7 7 0 0 0 7 18h9", "M3 3l18 18")
    val Play: ImageVector get() = filled("Play", "M6 4.5v15l12-7.5z")

    private const val HEART_PATH =
        "M12 21s-7-4.6-9.3-9C1 9 2.5 5 6 5c2 0 3.2 1.3 6 4 2.8-2.7 4-4 6-4 3.5 0 5 4 3.3 7C19 16.4 12 21 12 21z"
    private const val STAR_PATH =
        "M12 2l2.9 6.2 6.6.8-4.9 4.6 1.3 6.6L12 17.8 6.1 20.8l1.3-6.6L2.5 9.6l6.6-.8z"

    // Material Design "CheckCircle" (filled, check carved out).
    private const val CHECK_CIRCLE_PATH =
        "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"

    /** Stroked rounded rect (2dp corner radius per the drawing rules). */
    private fun roundedRect(x: Float, y: Float, w: Float, h: Float, r: Float = 2f): String = "M${x + r} $y h${w - 2 * r} a$r $r 0 0 1 $r $r v${h - 2 * r} a$r $r 0 0 1 -$r $r " +
        "h-${w - 2 * r} a$r $r 0 0 1 -$r -$r v-${h - 2 * r} a$r $r 0 0 1 $r -$r z"

    private val cache = mutableMapOf<String, ImageVector>()

    private fun filled(name: String, vararg pathData: String): ImageVector = cache.getOrPut(name) { build(name, pathData, filled = true) }

    private fun stroked(name: String, vararg pathData: String): ImageVector = cache.getOrPut(name) { build(name, pathData, filled = false) }

    private fun build(name: String, pathData: Array<out String>, filled: Boolean): ImageVector {
        val builder = ImageVector
            .Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        pathData.forEach { data ->
            val nodes = PathParser().parsePathString(data).toNodes()
            if (filled) {
                builder.addPath(pathData = nodes, fill = SolidColor(Color.Black))
            } else {
                builder.addPath(
                    pathData = nodes,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        return builder.build()
    }
}
