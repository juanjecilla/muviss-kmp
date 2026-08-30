package com.codingpit.muviss.widget

import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The widget's decisions, tested where they can be reached.
 *
 * Glance renders through the app-widget host, not through Skiko, so
 * `runComposeUiTest` cannot compose `MuvissWidget` — the composable is
 * verified on a device. Everything it decides *before* composing lives in
 * `watchNextWidgetUi`, and that is what these assert.
 */
class WatchNextWidgetStateTest {

    private val got = MediaId.tmdbTv("1399")
    private val firefly = MediaId.tmdbTv("1437")

    private fun episode(show: MediaId, season: Int, number: Int, name: String) = Episode(
        id = EpisodeId(show, season, number),
        seasonNumber = season,
        episodeNumber = number,
        name = name,
        airDateEpochDay = 50,
    )

    private fun item(
        show: MediaId,
        title: String,
        next: Episode? = episode(show, 1, 1, "Winter Is Coming"),
        seen: Int = 3,
        aired: Int = 10,
    ) = WatchNextItem(show, title, posterUrl = null, nextEpisode = next, seenCount = seen, airedCount = aired)

    @Test
    fun `an empty library says nothing is in progress`() {
        assertEquals(
            WatchNextWidgetUi.Empty.NOTHING_IN_PROGRESS,
            watchNextWidgetUi(emptyList(), WidgetSize.MEDIUM),
        )
    }

    /**
     * The upgrade case ADR 0015 accepts: titles are in progress, the catalog
     * table is still empty, so no episode can be named. Saying "open the app"
     * is the difference between a widget that looks broken and one that tells
     * you what to do.
     */
    @Test
    fun `rows with no episode at all mean the catalog has not arrived`() {
        val ui = watchNextWidgetUi(listOf(item(got, "Game of Thrones", next = null)), WidgetSize.MEDIUM)

        assertEquals(WatchNextWidgetUi.Empty.NO_CATALOG_YET, ui)
    }

    @Test
    fun `one named episode is enough to render rows`() {
        val ui = watchNextWidgetUi(
            listOf(item(got, "Game of Thrones", next = null), item(firefly, "Firefly")),
            WidgetSize.MEDIUM,
        )

        val rows = (ui as WatchNextWidgetUi.Rows).rows
        assertEquals(listOf("Game of Thrones", "Firefly"), rows.map { it.title })
        assertNull(rows.first().tickable, "a row with no catalog has nothing to tick")
        assertEquals(EpisodeId(firefly, 1, 1), rows.last().tickable)
    }

    @Test
    fun `a row names its next episode`() {
        val ui = watchNextWidgetUi(listOf(item(got, "Game of Thrones")), WidgetSize.SMALL)

        val row = (ui as WatchNextWidgetUi.Rows).rows.single()
        assertEquals("S1 · E1", row.episodeLabel)
        assertEquals("Winter Is Coming", row.episodeName)
        assertEquals(0.3f, row.progress)
    }

    @Test
    fun `each size takes as many rows as it can show`() {
        val many = (1..8).map { item(MediaId.tmdbTv(it.toString()), "Show $it") }

        assertEquals(1, (watchNextWidgetUi(many, WidgetSize.SMALL) as WatchNextWidgetUi.Rows).rows.size)
        assertEquals(3, (watchNextWidgetUi(many, WidgetSize.MEDIUM) as WatchNextWidgetUi.Rows).rows.size)
        assertEquals(5, (watchNextWidgetUi(many, WidgetSize.LARGE) as WatchNextWidgetUi.Rows).rows.size)
    }

    @Test
    fun `size is chosen from the measured height`() {
        assertEquals(WidgetSize.SMALL, WidgetSize.forHeightDp(60))
        assertEquals(WidgetSize.MEDIUM, WidgetSize.forHeightDp(128))
        assertEquals(WidgetSize.LARGE, WidgetSize.forHeightDp(220))
        assertEquals(WidgetSize.LARGE, WidgetSize.forHeightDp(2000), "a very tall widget still stops at five")
    }

    /**
     * The tick has already landed by the time the widget redraws, so the
     * ticked episode is no longer `nextEpisode` anywhere. Matching the undo
     * marker on the *show* is what keeps the banner on the row the person
     * actually touched.
     */
    @Test
    fun `the just-ticked show offers Undo instead of a tick`() {
        val ticked = EpisodeId(got, 1, 1)
        val ui = watchNextWidgetUi(
            listOf(item(got, "Game of Thrones", next = episode(got, 1, 2, "The Kingsroad")), item(firefly, "Firefly")),
            WidgetSize.MEDIUM,
            justTicked = ticked,
        )

        val rows = (ui as WatchNextWidgetUi.Rows).rows
        assertEquals(ticked, rows.first().undoable)
        assertNull(rows.last().undoable, "only the row that was ticked offers Undo")
    }

    @Test
    fun `a title with nothing aired has no progress bar`() {
        val ui = watchNextWidgetUi(listOf(item(got, "Game of Thrones", aired = 0, seen = 0)), WidgetSize.SMALL)

        assertNull((ui as WatchNextWidgetUi.Rows).rows.single().progress)
    }

    /**
     * A title whose catalog never arrives still offers Undo after a tick —
     * otherwise the one row the person just interacted with would be the one
     * that silently swallowed it.
     */
    @Test
    fun `an unnamed row still renders when it is the one just ticked`() {
        val ui = watchNextWidgetUi(
            listOf(item(got, "Game of Thrones", next = null)),
            WidgetSize.MEDIUM,
            justTicked = EpisodeId(got, 1, 1),
        )

        assertEquals(EpisodeId(got, 1, 1), (ui as WatchNextWidgetUi.Rows).rows.single().undoable)
    }
}
