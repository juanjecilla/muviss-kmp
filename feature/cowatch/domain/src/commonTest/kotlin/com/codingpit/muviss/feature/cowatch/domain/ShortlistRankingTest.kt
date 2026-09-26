package com.codingpit.muviss.feature.cowatch.domain

import com.codingpit.muviss.feature.cowatch.api.ShortlistReason
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `underscore_case` — see [InviteCodeTest]. */
class ShortlistRankingTest {

    @Suppress("LongParameterList") // a row builder: one named default per field, so a case overrides only what it asserts on
    private fun item(
        id: String,
        title: String = id,
        runtimeMinutes: Int? = 100,
        started: Boolean = false,
        seen: Boolean = false,
        pinned: Boolean = false,
    ) = PoolItem(
        mediaId = MediaId.tmdbMovie(id),
        mediaType = MediaType.MOVIE,
        title = title,
        posterUrl = null,
        genres = emptyList(),
        runtimeMinutes = runtimeMinutes,
        started = started,
        seen = seen,
        pinned = pinned,
    )

    @Test
    fun only_titles_in_both_pools_are_candidates() {
        val result = ShortlistRanking.rank(
            mine = listOf(item("1"), item("2")),
            theirs = listOf(item("2"), item("3")),
        )
        assertEquals(listOf(MediaId.tmdbMovie("2")), result.map { it.mediaId })
    }

    @Test
    fun a_title_neither_has_is_not_invented_from_one_side() {
        assertTrue(ShortlistRanking.rank(mine = listOf(item("1")), theirs = emptyList()).isEmpty())
        assertTrue(ShortlistRanking.rank(mine = emptyList(), theirs = listOf(item("1"))).isEmpty())
    }

    @Test
    fun both_pinned_outranks_everything_else() {
        val result = ShortlistRanking.rank(
            mine = listOf(item("plain"), item("pinned", runtimeMinutes = 240, pinned = true)),
            theirs = listOf(item("plain"), item("pinned", runtimeMinutes = 240, pinned = true)),
        )
        // Even though it is much longer, and length is the tiebreak.
        assertEquals(MediaId.tmdbMovie("pinned"), result.first().mediaId)
        assertTrue(ShortlistReason.BOTH_PINNED in result.first().reasons)
    }

    @Test
    fun one_sided_pinning_is_not_agreement() {
        val result = ShortlistRanking.rank(
            mine = listOf(item("a", pinned = true)),
            theirs = listOf(item("a", pinned = false)),
        )
        assertTrue(ShortlistReason.BOTH_PINNED !in result.single().reasons)
    }

    @Test
    fun something_neither_has_started_outranks_something_one_of_them_has() {
        val result = ShortlistRanking.rank(
            mine = listOf(item("fresh"), item("underway", started = true)),
            theirs = listOf(item("fresh"), item("underway")),
        )
        assertEquals(
            listOf(MediaId.tmdbMovie("fresh"), MediaId.tmdbMovie("underway")),
            result.map { it.mediaId },
        )
    }

    @Test
    fun ties_break_on_the_shorter_runtime() {
        val result = ShortlistRanking.rank(
            mine = listOf(item("long", runtimeMinutes = 180), item("short", runtimeMinutes = 90)),
            theirs = listOf(item("long", runtimeMinutes = 180), item("short", runtimeMinutes = 90)),
        )
        assertEquals(listOf(MediaId.tmdbMovie("short"), MediaId.tmdbMovie("long")), result.map { it.mediaId })
    }

    @Test
    fun an_unknown_runtime_sorts_last_rather_than_first() {
        // "We have an hour" is a real question; a title that cannot answer it
        // should not win on it.
        val result = ShortlistRanking.rank(
            mine = listOf(item("unknown", runtimeMinutes = null), item("known", runtimeMinutes = 200)),
            theirs = listOf(item("unknown", runtimeMinutes = null), item("known", runtimeMinutes = 200)),
        )
        assertEquals(listOf(MediaId.tmdbMovie("known"), MediaId.tmdbMovie("unknown")), result.map { it.mediaId })
    }

    @Test
    fun the_order_is_stable_and_does_not_depend_on_which_pool_arrived_first() {
        val mine = listOf(item("b", runtimeMinutes = 100), item("a", runtimeMinutes = 100))
        val theirs = listOf(item("a", runtimeMinutes = 100), item("b", runtimeMinutes = 100))
        assertEquals(
            ShortlistRanking.rank(mine, theirs).map { it.mediaId },
            ShortlistRanking.rank(mine.reversed(), theirs.reversed()).map { it.mediaId },
        )
    }

    @Test
    fun a_title_either_of_them_has_seen_is_explained_as_a_revisit() {
        val result = ShortlistRanking.rank(
            mine = listOf(item("a", seen = true)),
            theirs = listOf(item("a")),
        )
        assertTrue(ShortlistReason.REVISIT in result.single().reasons)
    }

    @Test
    fun our_own_snapshot_wins_for_the_title_and_theirs_fills_a_gap() {
        val result = ShortlistRanking.rank(
            mine = listOf(item("a", title = "Ours").copy(posterUrl = null, runtimeMinutes = null)),
            theirs = listOf(item("a", title = "Theirs").copy(posterUrl = "poster", runtimeMinutes = 120)),
        )
        val only = result.single()
        assertEquals("Ours", only.title)
        assertEquals("poster", only.posterUrl)
        assertEquals(120, only.runtimeMinutes)
    }
}
