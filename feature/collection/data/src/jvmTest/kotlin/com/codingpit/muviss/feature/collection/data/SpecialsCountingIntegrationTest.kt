@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.data

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.turbine.test
import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import com.codingpit.muviss.core.common.widget.NoOpWidgetRefresher
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.model.CountedProgress
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.feature.collection.domain.CollectionEntry
import com.codingpit.muviss.feature.progress.data.SqlDelightProgressRepository
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Season 0 counts toward a title's progress only by the per-device setting, read in SQL (#938, ADR 0038). These run
 * the real queries, because the parts that matter are the joins and the reconciliation writes, not the Kotlin.
 *
 * Day 1000 is "today". Regular episode 1 and the special aired on day 900; regular episode 2 airs on day 900 unless a
 * test says otherwise.
 */
class SpecialsCountingIntegrationTest {

    private val clock = FakeClock(1_000L * 86_400_000L)
    private val show = MediaId.tmdbTv("1399")
    private val regular1 = EpisodeId(show, 1, 1)
    private val regular2 = EpisodeId(show, 1, 2)
    private val special = EpisodeId(show, 0, 1)

    private class Library(val database: MuvissDatabase, val repository: SqlDelightCollectionRepository)

    private fun library(): Library {
        val database = inMemoryDatabase()
        val repository = SqlDelightCollectionRepository(database.collectionEntryQueries, ImmediateDispatchers(UnconfinedTestDispatcher()), clock)
        return Library(database, repository)
    }

    private fun details(secondRegularAirDay: Long = 900) = MediaDetails(
        summary = MediaSummary(show, "Show"),
        productionStatus = ProductionStatus.RETURNING,
        seasons = listOf(
            Season(0, "Specials", listOf(Episode(special, 0, 1, "Special", airDateEpochDay = 900))),
            Season(1, "Season 1", listOf(Episode(regular1, 1, 1, "E1", airDateEpochDay = 900), Episode(regular2, 1, 2, "E2", airDateEpochDay = secondRegularAirDay))),
        ),
    )

    private suspend fun MuvissDatabase.tick(id: EpisodeId, seen: Boolean = true) {
        episodeProgressQueries.upsert(id.toString(), id.show.toString(), id.seasonNumber.toLong(), id.episodeNumber.toLong(), seen, 1L, true)
    }

    /** The special in the cached catalog, as a catalog fetch writes it. Not called for the empty-cache cases. */
    private suspend fun MuvissDatabase.cacheSpecial() {
        episodeQueries.upsert(special.toString(), show.toString(), 0L, "Specials", 1L, "Special", 900L, null, null, 1L)
    }

    private suspend fun MuvissDatabase.setSpecialsCounted(enabled: Boolean) {
        appSettingsQueries.ensureRow()
        appSettingsQueries.updateCountSpecialsTowardProgress(enabled)
    }

    private suspend fun Library.entry(): CollectionEntry = repository.observeEntry(show).first()!!

    @Test
    fun a_seen_special_is_not_counted_while_the_setting_is_off() = runTest {
        val library = library()
        library.repository.upsertSnapshot(details())
        library.database.cacheSpecial()
        library.database.tick(regular1)
        library.database.tick(regular2)
        library.database.tick(special)

        val entry = library.entry()

        assertEquals(CountedProgress(seen = 2, aired = 2), entry.counted)
        assertEquals(WatchStatus.WATCHED, entry.status)
    }

    @Test
    fun an_unseen_aired_special_keeps_the_show_watching_only_while_the_setting_is_on() = runTest {
        val library = library()
        library.repository.upsertSnapshot(details())
        library.database.cacheSpecial()
        library.database.tick(regular1)
        library.database.tick(regular2)
        library.database.tick(special, seen = false)

        assertEquals(WatchStatus.WATCHED, library.entry().status)

        library.database.setSpecialsCounted(true)

        val counted = library.entry()
        assertEquals(CountedProgress(seen = 2, aired = 3), counted.counted)
        assertEquals(WatchStatus.WATCHING, counted.status)
    }

    @Test
    fun the_library_and_a_single_entry_read_the_same_counts() = runTest {
        val library = library()
        library.database.setSpecialsCounted(true)
        library.repository.upsertSnapshot(details())
        library.database.cacheSpecial()
        library.database.tick(regular1)
        library.database.tick(special)

        val fromLibrary = library.repository.observeAll().first().single()
        val fromEntry = library.entry()

        assertEquals(fromEntry.counted, fromLibrary.counted)
        assertEquals(CountedProgress(seen = 2, aired = 3), fromEntry.counted)
    }

    @Test
    fun a_special_ticked_before_the_catalog_is_cached_is_not_counted_even_with_the_setting_on() = runTest {
        // An upgraded install's `episode` cache is empty until the app runs once, so a tick has no aired catalog row to join.
        val library = library()
        library.database.setSpecialsCounted(true)
        library.repository.upsertSnapshot(details())
        library.database.tick(regular1)
        library.database.tick(special)

        val entry = library.entry()

        assertEquals(CountedProgress(seen = 1, aired = 2), entry.counted)
        assertEquals(WatchStatus.WATCHING, entry.status)
    }

    @Test
    fun raising_the_snapshot_after_a_pull_ignores_a_special_tick() = runTest {
        val library = library()
        // Only the first regular episode had aired when the snapshot was taken.
        library.repository.upsertSnapshot(details(secondRegularAirDay = 2_000))
        library.database.tick(regular1)
        library.database.tick(special)

        library.database.collectionEntryQueries.raiseAiredToSeen(listOf(show.toString()))

        val stored = library.database.collectionEntryQueries.selectById(show.toString()).awaitAsOne()
        assertEquals(1L, stored.airedEpisodes, "a special tick must not raise the snapshot's aired count")
        assertEquals(1, library.entry().airedEpisodes)
    }

    @Test
    fun a_special_that_airs_at_midnight_is_counted_without_a_write() = runTest {
        // 23:59:59 on day 1000. The special airs on day 1001 and is never ticked, so the title is Watched until the clock
        // passes midnight, and Watching after, with no write in between (#951).
        val database = inMemoryDatabase()
        val clock = FakeClock(epochMsAtStartOfDay(1_000L) + 86_399_000L)
        val repository = SqlDelightCollectionRepository(database.collectionEntryQueries, ImmediateDispatchers(UnconfinedTestDispatcher(testScheduler)), clock)
        database.setSpecialsCounted(true)
        repository.upsertSnapshot(
            MediaDetails(
                summary = MediaSummary(show, "Show"),
                productionStatus = ProductionStatus.RETURNING,
                seasons = listOf(
                    Season(0, "Specials", listOf(Episode(special, 0, 1, "Special", airDateEpochDay = 1_001L))),
                    Season(1, "Season 1", listOf(Episode(regular1, 1, 1, "E1", airDateEpochDay = 900L))),
                ),
            ),
        )
        database.episodeQueries.upsert(special.toString(), show.toString(), 0L, "Specials", 1L, "Special", 1_001L, null, null, 1L)
        database.tick(regular1)

        repository.observeEntry(show).test {
            assertEquals(WatchStatus.WATCHED, awaitItem()!!.status)

            clock.advanceBy(1_000L)
            this@runTest.advanceTimeBy(1_000L)

            assertEquals(WatchStatus.WATCHING, awaitItem()!!.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun profile_stats_count_a_special_tick_whatever_the_setting() = runTest {
        // Stats describe what was watched, not progress: a special's viewing is a Play, read whatever the setting says (#950, ADR 0038).
        val database = inMemoryDatabase()
        val clock = FakeClock(epochMsAtStartOfDay(1_000L))
        val progress = SqlDelightProgressRepository(
            database.episodeProgressQueries,
            database.episodePlayQueries,
            ImmediateDispatchers(UnconfinedTestDispatcher(testScheduler)),
            clock,
            NoOpWidgetRefresher,
        )
        database.setSpecialsCounted(false)
        progress.setSeen(special, seen = true)
        assertEquals(setOf(1_000L), progress.observeSeenActivityEpochDays().first())

        database.setSpecialsCounted(true)
        assertEquals(setOf(1_000L), progress.observeSeenActivityEpochDays().first())
    }

    /** A provider that has not caught up: the regular episodes are not aired yet, and the special is. */
    private fun laggingDetails() = MediaDetails(
        summary = MediaSummary(show, "Show"),
        productionStatus = ProductionStatus.RETURNING,
        seasons = listOf(
            Season(0, "Specials", listOf(Episode(special, 0, 1, "Special", airDateEpochDay = 900))),
            Season(1, "Season 1", listOf(Episode(regular1, 1, 1, "E1", airDateEpochDay = 5_000), Episode(regular2, 1, 2, "E2", airDateEpochDay = 5_000))),
        ),
    )

    /**
     * #938 review: the refresh floor counted every seen tick, so two regular ticks plus a special floored the snapshot at
     * three. The snapshot holds regular episodes only, whatever the setting and whether the special is cached.
     */
    @Test
    fun a_refresh_floor_ignores_a_seen_special_whatever_the_setting_and_cache() = runTest {
        for (counted in listOf(false, true)) {
            for (cached in listOf(false, true)) {
                val library = library()
                library.database.setSpecialsCounted(counted)
                library.repository.upsertSnapshot(details())
                if (cached) library.database.cacheSpecial()
                library.database.tick(regular1)
                library.database.tick(regular2)
                library.database.tick(special)

                library.repository.upsertSnapshot(laggingDetails())

                val stored = library.database.collectionEntryQueries.selectById(show.toString()).awaitAsOne()
                assertEquals(2L, stored.airedEpisodes, "aired floor, counted=$counted cached=$cached")
                assertEquals(2L, stored.totalEpisodes, "total floor, counted=$counted cached=$cached")

                val expected = when {
                    !counted -> CountedProgress(seen = 2, aired = 2)
                    cached -> CountedProgress(seen = 3, aired = 3)
                    else -> CountedProgress(seen = 2, aired = 2)
                }
                assertEquals(expected, library.entry().counted, "counted=$counted cached=$cached")
            }
        }
    }
}
