package com.codingpit.muviss.feature.cowatch.domain

import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.cowatch.api.PoolSettings
import com.codingpit.muviss.feature.cowatch.api.PoolSource
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `underscore_case` — see [InviteCodeTest].
 *
 * This is the function whose output leaves the device, so the cases below are
 * as much about what is *absent* from a pool as what is in it.
 */
class WatchPoolBuilderTest {

    private fun summary(
        id: String,
        status: WatchStatus = WatchStatus.NOT_STARTED,
        revisitWillingness: Boolean? = null,
        pinned: Boolean = false,
    ) = CollectionSummary(
        mediaId = MediaId.tmdbMovie(id),
        title = id,
        posterUrl = null,
        status = status,
        revisitWillingness = revisitWillingness,
        coWatchPinned = pinned,
    )

    private fun settings(
        source: PoolSource = PoolSource.NotStarted,
        includeSeenByDefault: Boolean = false,
    ) = PoolSettings(source, includeSeenByDefault)

    @Test
    fun the_default_pool_is_what_the_user_has_not_started() {
        val pool = WatchPoolBuilder.build(
            listOf(summary("fresh"), summary("underway", WatchStatus.WATCHING)),
            settings(),
        )
        assertEquals(listOf(MediaId.tmdbMovie("fresh")), pool.map { it.mediaId })
    }

    @Test
    fun a_title_in_progress_is_never_published() {
        // Someone four seasons in is not a candidate to start it with you, and
        // publishing it would leak how far along they are for no gain.
        val pool = WatchPoolBuilder.build(
            listOf(summary("underway", WatchStatus.WATCHING, revisitWillingness = true, pinned = true)),
            settings(includeSeenByDefault = true),
        )
        assertTrue(pool.isEmpty())
    }

    @Test
    fun a_seen_title_stays_out_when_the_default_says_so() {
        val pool = WatchPoolBuilder.build(
            listOf(summary("seen", WatchStatus.WATCHED)),
            settings(includeSeenByDefault = false),
        )
        assertTrue(pool.isEmpty())
    }

    @Test
    fun a_seen_title_joins_when_the_default_says_so() {
        val pool = WatchPoolBuilder.build(
            listOf(summary("seen", WatchStatus.WATCHED), summary("finished", WatchStatus.FINISHED)),
            settings(includeSeenByDefault = true),
        )
        assertEquals(2, pool.size)
        assertTrue(pool.all { it.seen })
    }

    @Test
    fun an_explicit_yes_beats_a_default_of_no() {
        val pool = WatchPoolBuilder.build(
            listOf(summary("seen", WatchStatus.WATCHED, revisitWillingness = true)),
            settings(includeSeenByDefault = false),
        )
        assertEquals(listOf(MediaId.tmdbMovie("seen")), pool.map { it.mediaId })
    }

    @Test
    fun an_explicit_no_beats_a_default_of_yes() {
        // A default is a convenience; an answer is an instruction.
        val pool = WatchPoolBuilder.build(
            listOf(summary("seen", WatchStatus.WATCHED, revisitWillingness = false)),
            settings(includeSeenByDefault = true),
        )
        assertTrue(pool.isEmpty())
    }

    @Test
    fun a_named_list_replaces_the_default_source() {
        val pool = WatchPoolBuilder.build(
            listOf(summary("listed"), summary("unlisted")),
            settings(source = PoolSource.Named("list-1")),
            namedListContents = setOf(MediaId.tmdbMovie("listed")),
        )
        assertEquals(listOf(MediaId.tmdbMovie("listed")), pool.map { it.mediaId })
    }

    @Test
    fun a_named_list_still_does_not_publish_something_in_progress() {
        val pool = WatchPoolBuilder.build(
            listOf(summary("listed", WatchStatus.WATCHING)),
            settings(source = PoolSource.Named("list-1")),
            namedListContents = setOf(MediaId.tmdbMovie("listed")),
        )
        assertTrue(pool.isEmpty())
    }

    @Test
    fun the_pin_travels_with_the_title() {
        val pool = WatchPoolBuilder.build(listOf(summary("a", pinned = true)), settings())
        assertTrue(pool.single().pinned)
    }

    @Test
    fun a_published_item_says_whether_a_title_was_seen_but_never_how_far_or_when() {
        // The guard against this quietly growing. A pool row is a denormalized
        // snapshot, and the day it gains a tick count, a play, a date or a
        // rating is the day ADR 0022's purpose-limited invariant stops being
        // true. `started` and `seen` are two bits and must stay two bits.
        //
        // Asserted by construction rather than by reflection: `kotlin.reflect`
        // is not available on Kotlin/Native, and this is `commonTest`.
        val item = WatchPoolBuilder.build(
            listOf(summary("a", WatchStatus.WATCHED, revisitWillingness = true)),
            settings(),
        ).single()
        assertEquals(
            PoolItem(
                mediaId = MediaId.tmdbMovie("a"),
                mediaType = item.mediaType,
                title = "a",
                posterUrl = null,
                genres = emptyList(),
                runtimeMinutes = null,
                started = true,
                seen = true,
                pinned = false,
            ),
            item,
        )
    }
}
