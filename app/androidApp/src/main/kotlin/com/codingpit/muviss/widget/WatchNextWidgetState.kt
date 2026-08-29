package com.codingpit.muviss.widget

import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.models.EpisodeId

/** How many rows fit, decided from the widget's measured height. */
enum class WidgetSize(val rows: Int) {
    SMALL(1),
    MEDIUM(3),
    LARGE(5),
    ;

    companion object {
        /**
         * Android reports a widget's size in dp, not in cells. These bounds
         * are the standard 1x1 / 2x2 / 4x4-ish heights on a default launcher
         * grid; anything taller than [LARGE] still shows five rows, because a
         * home screen full of one app's episodes stops being glanceable.
         */
        fun forHeightDp(heightDp: Int): WidgetSize = when {
            heightDp < MEDIUM_MIN_HEIGHT_DP -> SMALL
            heightDp < LARGE_MIN_HEIGHT_DP -> MEDIUM
            else -> LARGE
        }

        private const val MEDIUM_MIN_HEIGHT_DP = 128
        private const val LARGE_MIN_HEIGHT_DP = 220
    }
}

/** One rendered line of the widget. */
data class WidgetRow(
    val mediaId: String,
    val title: String,
    val posterUrl: String?,
    /** "S2 · E5" for a show, null when no episode can be named. */
    val episodeLabel: String?,
    val episodeName: String?,
    val progress: Float?,
    /** The episode a tick would write, or null when the row has nothing to tick. */
    val tickable: EpisodeId?,
    /** Set on the one row that was just ticked: it shows "Undo" instead of a tick button. */
    val undoable: EpisodeId?,
)

/** Everything the widget can be showing. */
sealed interface WatchNextWidgetUi {

    /** Rows to render, already truncated to the widget's height. */
    data class Rows(val rows: List<WidgetRow>) : WatchNextWidgetUi

    /**
     * Nothing to show, with the reason — the two empties are genuinely
     * different situations and telling them apart is the difference between
     * a widget that looks broken and one that tells you what to do.
     */
    enum class Empty : WatchNextWidgetUi {
        /** No show is in progress. Adding one is an app-side action. */
        NOTHING_IN_PROGRESS,

        /**
         * Titles are in progress but no episode can be named for any of them,
         * which after an upgrade means the catalog table is still empty (ADR
         * 0013 ships no backfill). Opening the app once fills it.
         */
        NO_CATALOG_YET,
    }
}

/**
 * Turns the shared watch-next answer into what this widget draws.
 *
 * Pure, and separate from the Glance composable on purpose: Glance renders
 * through the app-widget host rather than through Skiko, so `runComposeUiTest`
 * cannot see it and the composition itself is not testable here. Every
 * decision worth asserting — how many rows, which empty state, which row
 * offers Undo, what a row with no catalog does — lives in this function
 * instead, where a plain unit test reaches it.
 */
fun watchNextWidgetUi(
    items: List<WatchNextItem>,
    size: WidgetSize,
    justTicked: EpisodeId? = null,
): WatchNextWidgetUi {
    if (items.isEmpty()) return WatchNextWidgetUi.Empty.NOTHING_IN_PROGRESS

    val rows = items.take(size.rows).map { item -> item.toRow(justTicked) }

    // Every row unnamed means the catalog has not arrived, not that the user
    // is caught up on everything — a caught-up title leaves `Watching` and
    // would not be in this list at all.
    if (rows.all { it.episodeLabel == null && it.undoable == null }) {
        return WatchNextWidgetUi.Empty.NO_CATALOG_YET
    }
    return WatchNextWidgetUi.Rows(rows)
}

private fun WatchNextItem.toRow(justTicked: EpisodeId?): WidgetRow {
    // The ticked episode is gone from `nextEpisode` the moment the write
    // lands, so the banner is matched against the row's title rather than its
    // episode: it is that show's row that must keep offering Undo.
    val undoable = justTicked?.takeIf { it.show == mediaId }
    return WidgetRow(
        mediaId = mediaId.toString(),
        title = title,
        posterUrl = posterUrl,
        episodeLabel = nextEpisode?.let { "S${it.seasonNumber} · E${it.episodeNumber}" },
        episodeName = nextEpisode?.name,
        progress = progress,
        tickable = nextEpisode?.id,
        undoable = undoable,
    )
}
