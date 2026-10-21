package com.codingpit.muviss.core.designsystem.theme

import androidx.compose.ui.unit.dp

/**
 * 4dp base spacing grid. xs icon-to-label, s intra-component, m grid gutter/
 * list row padding, l screen edge margin (compact) + card padding, xl section
 * spacing + edge margin (medium), xxl major breaks + edge margin (expanded),
 * huge empty-state rhythm.
 */
object MuvissSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val huge = 48.dp

    val bottomContent = 32.dp

    /**
     * [bottomContent] plus the room a Material snackbar takes (about 68dp tall with its 8dp
     * margin, rounded up). A scrolling list with an undo snackbar over it ends with this, so
     * its last row can be scrolled clear of the snackbar rather than sitting under it.
     */
    val bottomContentWithSnackbar = bottomContent + 72.dp
}
