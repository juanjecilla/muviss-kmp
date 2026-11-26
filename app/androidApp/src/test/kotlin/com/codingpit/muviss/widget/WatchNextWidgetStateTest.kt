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
    ) = WatchNextItem(
        show,
        title,
        posterUrl = null,
        nextEpisode = next,
        seenCount = seen,
        airedCount = aired,
        catalogLoaded = true,
    )

    /** A title whose catalog has not arrived: nothing is known about its episodes yet. */
    private fun notArrived(show: MediaId, title: String) = WatchNextItem(
        show,
        title,
        posterUrl = null,
        nextEpisode = null,
        catalogLoaded = false,
    )

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
    fun `rows with no episode and no loaded catalog mean the catalog has not arrived`() {
        val ui = watchNextWidgetUi(
            listOf(notArrived(got, "Game of Thrones")),
            WidgetSize.MEDIUM,
        )

        assertEquals(WatchNextWidgetUi.Empty.NO_CATALOG_YET, ui)
    }

    /**
     * A loaded catalog that has aired episodes is not a missing one, even when no regular episode is left to name.
     * Before the fix this read "open the app", which changes nothing: the state only clears once the special is ticked.
     */
    @Test
    fun `aired episodes with no next one never read as a missing catalog`() {
        val ui = watchNextWidgetUi(
            listOf(item(got, "Game of Thrones", next = null, seen = 9, aired = 10)),
            WidgetSize.MEDIUM,
        )

        assertEquals(1, (ui as WatchNextWidgetUi.Rows).rows.size)
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

    /**
     * The confirmation names the episode the tick wrote, not the one the row
     * now points at: after the tick the next episode has moved on to E2.
     */
    @Test
    fun `the just-ticked row names the episode it marked, not the next one`() {
        val ui = watchNextWidgetUi(
            listOf(item(got, "Game of Thrones", next = episode(got, 1, 2, "The Kingsroad"))),
            WidgetSize.SMALL,
            justTicked = EpisodeId(got, 1, 1),
        )

        val row = (ui as WatchNextWidgetUi.Rows).rows.single()
        assertEquals("S1 · E1", row.tickedLabel)
        assertEquals("S1 · E2", row.episodeLabel, "the row still advances to the next episode")
    }

    @Test
    fun `only the just-ticked row carries a ticked label`() {
        val ui = watchNextWidgetUi(
            listOf(item(got, "Game of Thrones"), item(firefly, "Firefly")),
            WidgetSize.MEDIUM,
            justTicked = EpisodeId(got, 1, 1),
        )

        val rows = (ui as WatchNextWidgetUi.Rows).rows
        assertEquals("S1 · E1", rows.first().tickedLabel)
        assertNull(rows.last().tickedLabel)
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

    /**
     * The empty state is the way into the Pick Session (EPIC 51, #700): a person with nothing in
     * progress has nothing to tick, so tapping the widget starts a pick instead of dead-ending.
     */
    @Test
    fun `an empty library makes the empty state open the Pick Session`() {
        val ui = watchNextWidgetUi(emptyList(), WidgetSize.MEDIUM)

        assertEquals(EmptyAction.OPEN_NEXT_PICK, (ui as WatchNextWidgetUi.Empty).action)
    }

    @Test
    fun `the Pick Session action is the same at every widget size`() {
        for (size in WidgetSize.entries) {
            assertEquals(
                EmptyAction.OPEN_NEXT_PICK,
                (watchNextWidgetUi(emptyList(), size) as WatchNextWidgetUi.Empty).action,
                "size $size",
            )
        }
    }

    /**
     * The catalog-not-arrived empty says "open Muviss once", and its action stays the app: the fix
     * for it is the app's own first-open catalog download, not a pick.
     */
    @Test
    fun `the catalog-not-arrived empty still opens the app rather than the Pick Session`() {
        val ui = watchNextWidgetUi(listOf(notArrived(got, "Game of Thrones")), WidgetSize.MEDIUM)

        assertEquals(WatchNextWidgetUi.Empty.NO_CATALOG_YET, ui)
        assertEquals(EmptyAction.OPEN_APP, (ui as WatchNextWidgetUi.Empty).action)
    }

    /**
     * #888. The last regular episode is ticked and only an aired special is left, so the shared list (which drops
     * a title whose only unseen episodes are specials) no longer holds the show. The widget keeps its row.
     */
    @Test
    fun `the last regular tick keeps the title row with Undo when only an aired special is left`() {
        val ticked = EpisodeId(got, 1, 10)
        val ui = watchNextWidgetUi(
            listOf(item(firefly, "Firefly")),
            WidgetSize.MEDIUM,
            justTicked = ticked,
            justTickedTitle = "Game of Thrones",
        )

        val rows = (ui as WatchNextWidgetUi.Rows).rows
        assertEquals(2, rows.size)
        val kept = rows.first()
        assertEquals(got.toString(), kept.mediaId)
        assertEquals("Game of Thrones", kept.title)
        assertEquals(ticked, kept.undoable)
        assertEquals("S1 · E10", kept.tickedLabel)
        assertNull(kept.tickable, "a kept row offers Undo, not a tick")
        assertNull(rows.last().undoable)
    }

    /** #888. A completing tick takes the title out of WATCHING, so it is absent from the list the same way. */
    @Test
    fun `a completing tick keeps the title row with Undo instead of the empty state`() {
        val ticked = EpisodeId(got, 8, 6)
        val ui = watchNextWidgetUi(
            emptyList(),
            WidgetSize.SMALL,
            justTicked = ticked,
            justTickedTitle = "Game of Thrones",
        )

        val rows = (ui as WatchNextWidgetUi.Rows).rows
        assertEquals(1, rows.size)
        assertEquals(ticked, rows.single().undoable)
        assertEquals("S8 · E6", rows.single().tickedLabel)
    }

    /** #888. The kept row takes one of the widget's slots, so the size still bounds the list. */
    @Test
    fun `the kept row counts toward the widget's row budget`() {
        val ui = watchNextWidgetUi(
            listOf(item(firefly, "Firefly"), item(MediaId.tmdbTv("60625"), "Rick and Morty")),
            WidgetSize.SMALL,
            justTicked = EpisodeId(got, 1, 10),
            justTickedTitle = "Game of Thrones",
        )

        val rows = (ui as WatchNextWidgetUi.Rows).rows
        assertEquals(listOf("Game of Thrones"), rows.map { it.title })
    }

    /** #888. A marker without a title (an older build's, or a title that was not found) keeps the old behaviour. */
    @Test
    fun `without a remembered title an emptied list still says nothing is in progress`() {
        val ui = watchNextWidgetUi(
            emptyList(),
            WidgetSize.MEDIUM,
            justTicked = EpisodeId(got, 1, 10),
            justTickedTitle = null,
        )

        assertEquals(WatchNextWidgetUi.Empty.NOTHING_IN_PROGRESS, ui)
    }

    /** #888. While the title is still listed, its own row carries Undo and nothing is duplicated. */
    @Test
    fun `a title still in the list is not kept twice`() {
        val ticked = EpisodeId(got, 1, 1)
        val ui = watchNextWidgetUi(
            listOf(item(got, "Game of Thrones", next = episode(got, 1, 2, "The Kingsroad")), item(firefly, "Firefly")),
            WidgetSize.MEDIUM,
            justTicked = ticked,
            justTickedTitle = "Game of Thrones",
        )

        val rows = (ui as WatchNextWidgetUi.Rows).rows
        assertEquals(2, rows.size)
        assertEquals(1, rows.count { it.undoable != null })
    }
}
