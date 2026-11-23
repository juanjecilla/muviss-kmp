package com.codingpit.muviss.ios

import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetUndoMarkerTest {

    @Test
    fun open_suite_returns_a_usable_suite_for_an_ordinary_name() {
        val suite = WidgetUndoMarker.openSuite("muviss.test.${NSUUID().UUIDString()}")

        assertNotNull(suite)
    }

    // NSGlobalDomain is a suite Foundation refuses: NSUserDefaults(suiteName:) throws
    // NullPointerException for it on the simulator (probed; the bundle id is null in the test host,
    // so it cannot be used). openSuite must turn that throw into null.
    @Test
    fun open_suite_returns_null_for_a_suite_the_system_refuses() {
        assertNull(WidgetUndoMarker.openSuite(REFUSED_SUITE))
    }

    @Test
    fun marker_over_a_refused_suite_offers_no_undo_and_ignores_writes() {
        val marker = WidgetUndoMarker(WidgetUndoMarker.openSuite(REFUSED_SUITE))

        marker.markJustTicked("550/1/2")
        marker.clear()

        assertNull(marker.justTicked())
    }

    /** #888: the ticked title is stored beside the episode, so the widget can keep the row, and clear drops both. */
    @Test
    fun the_ticked_title_is_kept_with_the_marker_until_clear() {
        val marker = WidgetUndoMarker(WidgetUndoMarker.openSuite("muviss.test.${NSUUID().UUIDString()}"))

        marker.markJustTicked("550/1/2", "Game of Thrones")
        assertEquals("Game of Thrones", marker.justTickedTitle())

        marker.clear()
        assertNull(marker.justTickedTitle())
        assertNull(marker.justTicked())
    }

    /** #888: a completing tick takes the title out of Watch Next, so the list is empty and the widget keeps the row. */
    @Test
    fun a_completing_tick_keeps_the_title_row_with_undo() {
        val ticked = EpisodeId(GOT, 8, 6).toString()

        val rows = IosWidgetBridge.watchNextRowsFor(emptyList(), ticked, "Game of Thrones", limit = 5)

        assertUndoRowFor(rows, ticked, title = "Game of Thrones", label = "S8 · E6")
    }

    /**
     * #888: the last regular episode is ticked and only an aired special is left, so the shared list (which drops
     * the title) holds only another show. The kept row is first and the other show is still listed.
     */
    @Test
    fun the_last_regular_tick_keeps_the_title_row_with_undo_before_another_title() {
        val ticked = EpisodeId(GOT, 1, 10).toString()
        val firefly = item(FIREFLY, "Firefly")

        val rows = IosWidgetBridge.watchNextRowsFor(listOf(firefly), ticked, "Game of Thrones", limit = 5)

        assertUndoRowFor(rows, ticked, title = "Game of Thrones", label = "S1 · E10")
        assertEquals("Firefly", rows.last().title)
    }

    /** A repeated tick of the marked episode finds no Watch Next item, and keeps the remembered title. */
    @Test
    fun a_repeated_tick_of_the_marked_episode_keeps_the_remembered_title() {
        val ticked = EpisodeId(GOT, 8, 6).toString()

        assertEquals("Game of Thrones", rememberedTickTitle(ticked, null, ticked, "Game of Thrones"))
    }

    /** A tick of a different episode must not reuse another episode's remembered title. */
    @Test
    fun a_tick_of_a_different_episode_does_not_reuse_the_remembered_title() {
        val other = EpisodeId(FIREFLY, 1, 1).toString()
        val marked = EpisodeId(GOT, 8, 6).toString()

        assertNull(rememberedTickTitle(other, null, marked, "Game of Thrones"))
    }

    /** Asserts what the Swift widget reads: the kept row's identity, its Undo test, its tickability and its label. */
    private fun assertUndoRowFor(rows: List<IosWidgetRow>, ticked: String, title: String, label: String) {
        val first = rows.first()
        assertEquals(title, first.title)
        assertEquals(EpisodeId.parse(ticked).show.toString(), first.mediaId)
        assertTrue(ticked.startsWith(first.mediaId + "/"), "WatchNextView's isJustTicked matches on this prefix")
        assertNull(first.episodeId, "a kept row offers Undo, not a tick")
        assertTrue(first.catalogLoaded, "a loaded row stays out of the NO_CATALOG_YET branch")
        assertEquals(label, IosWidgetBridge.tickedLabel(ticked))
    }

    private fun item(show: MediaId, title: String) = WatchNextItem(
        mediaId = show,
        title = title,
        posterUrl = null,
        nextEpisode = Episode(
            id = EpisodeId(show, 1, 1),
            seasonNumber = 1,
            episodeNumber = 1,
            name = "Pilot",
            airDateEpochDay = 50,
        ),
        seenCount = 3,
        airedCount = 10,
        catalogLoaded = true,
    )

    private companion object {
        const val REFUSED_SUITE = "NSGlobalDomain"
        val GOT = MediaId.tmdbTv("1399")
        val FIREFLY = MediaId.tmdbTv("1437")
    }
}
