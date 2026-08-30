@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.EpisodePlayQueries
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class RewatchDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

private class FixedClock(private var millis: Long) : AppClock {
    override fun nowEpochMs(): Long = millis
    fun set(newMillis: Long) {
        millis = newMillis
    }
}

/**
 * The rewatch queries in `EpisodePlay.sq`, at the level where their
 * definition actually lives (ADR 0012).
 *
 * The case that motivates the whole CTE is [rewatchInMarchOfAnEpisodeFirstSeenInDecember]:
 * every other definition of "first play" gets that one wrong, and gets it
 * wrong silently — a plausible-looking zero rather than an error.
 */
class RewatchQueryTest {

    private lateinit var repository: SqlDelightProgressRepository
    private lateinit var playQueries: EpisodePlayQueries
    private lateinit var clock: FixedClock

    private val show = MediaId.tmdbTv("1399")
    private val ep1 = EpisodeId(show, 1, 1)
    private val ep2 = EpisodeId(show, 1, 2)
    private val movie = MediaId.tmdbMovie("603")

    // 2025-12-14 and 2026-03-02 / 2026-03-09, UTC, as epoch millis.
    private val dec2025 = 20_436L * MILLIS_PER_DAY
    private val mar2026 = 20_514L * MILLIS_PER_DAY
    private val laterMar2026 = 20_521L * MILLIS_PER_DAY
    private val startOf2026 = 20_454L * MILLIS_PER_DAY

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        val database = MuvissDatabase(driver)
        playQueries = database.episodePlayQueries
        clock = FixedClock(dec2025)
        repository = SqlDelightProgressRepository(
            database.episodeProgressQueries,
            playQueries,
            RewatchDispatchers(UnconfinedTestDispatcher()),
            clock,
        )
    }

    @Test
    fun `a first viewing is not a rewatch`() = runTest {
        repository.setSeen(ep1, seen = true)
        repository.setSeen(ep2, seen = true)

        assertEquals(emptyMap(), repository.observeRewatchCounts(ALL_TIME).first())
    }

    @Test
    fun `a second viewing of the same episode is one rewatch`() = runTest {
        repository.setSeen(ep1, seen = true)
        clock.set(mar2026)
        repository.recordPlay(ep1)

        assertEquals(mapOf(show to 1), repository.observeRewatchCounts(ALL_TIME).first())
    }

    /**
     * The reason "first play" is global rather than recomputed inside the
     * window. Under a window-local reading this episode has one play in 2026,
     * that play is its own first, and the year reports zero rewatches — for a
     * viewing the person would unhesitatingly call a rewatch.
     */
    @Test
    fun `rewatch in March of an episode first seen in December`() = runTest {
        repository.setSeen(ep1, seen = true) // December 2025
        clock.set(mar2026)
        repository.recordPlay(ep1)
        clock.set(laterMar2026)
        repository.recordPlay(ep1)

        assertEquals(mapOf(show to 3 - 1), repository.observeRewatchCounts(ALL_TIME).first())
        assertEquals(mapOf(show to 2), repository.observeRewatchCounts(startOf2026).first())
    }

    @Test
    fun `the window bounds the rewatch, never the first viewing`() = runTest {
        repository.setSeen(ep1, seen = true) // December 2025
        clock.set(dec2025 + 1)
        repository.recordPlay(ep1) // also December 2025

        assertEquals(mapOf(show to 1), repository.observeRewatchCounts(ALL_TIME).first())
        assertEquals(emptyMap(), repository.observeRewatchCounts(startOf2026).first())
    }

    /**
     * Two viewings recorded in the same millisecond are one viewing.
     *
     * This asserted the opposite until ADR 0013 gave `episodePlay` a
     * cross-device identity derived from the viewing —
     * `episodeId@watchedAtEpochMs` — so that the same viewing recorded on two
     * devices is the same row. Two plays of one episode at one millisecond
     * therefore collide on the primary key and collapse, where an
     * autoincrement id kept them apart.
     *
     * Accepted rather than worked around: a person cannot tap twice inside a
     * millisecond, and every path that writes several plays at one timestamp
     * writes them for *different* episodes, which have different ids. It is
     * reachable only from a test holding a fixed clock, as this one does.
     *
     * The `id` tiebreak in `rewatchCountsByMedia` still earns its place — it
     * disambiguates two *different* episodes sharing a timestamp inside the
     * MIN() aggregate, which bulk "mark season seen" produces constantly.
     */
    @Test
    fun `two viewings in the same millisecond are one viewing, so no rewatch`() = runTest {
        repository.setSeen(ep1, seen = true)
        repository.recordPlay(ep1) // clock has not moved

        assertEquals(emptyMap(), repository.observeRewatchCounts(ALL_TIME).first())
    }

    @Test
    fun `a second viewing a millisecond later is a rewatch`() = runTest {
        // The boundary the collapse above sits on: move the clock at all and
        // the two plays get distinct ids and count normally.
        repository.setSeen(ep1, seen = true)
        clock.set(clock.nowEpochMs() + 1)
        repository.recordPlay(ep1)

        assertEquals(mapOf(show to 1), repository.observeRewatchCounts(ALL_TIME).first())
    }

    @Test
    fun `over all time the count equals plays minus distinct episodes`() = runTest {
        repository.setSeen(ep1, seen = true)
        repository.setSeen(ep2, seen = true)
        clock.set(mar2026)
        repository.recordPlay(ep1)
        repository.recordPlay(ep2)
        repository.recordPlay(ep2)

        val plays = playQueries.selectAll().awaitAsList().size
        val distinct = playQueries.selectAll().awaitAsList().map { it.episodeId }.distinct().size
        assertEquals(mapOf(show to plays - distinct), repository.observeRewatchCounts(ALL_TIME).first())
    }

    /** A film's rewatch is a second viewing of its single synthetic element, recorded identically (ADR 0011). */
    @Test
    fun `a movie watched twice has one rewatch`() = runTest {
        repository.setSeen(EpisodeId.forMovie(movie), seen = true)
        clock.set(mar2026)
        repository.recordPlay(EpisodeId.forMovie(movie))

        assertEquals(mapOf(movie to 1), repository.observeRewatchCounts(ALL_TIME).first())
    }

    /**
     * Catching up is not rewatching: a bulk mark writes one viewing per
     * previously-unseen episode and skips the rest, so it can never
     * manufacture a rewatch (ADR 0011).
     */
    @Test
    fun `a bulk mark over already-seen episodes adds no rewatches`() = runTest {
        repository.setSeen(ep1, seen = true)
        clock.set(mar2026)
        repository.recordPlaysForUnseen(listOf(ep1, ep2))

        assertEquals(emptyMap(), repository.observeRewatchCounts(ALL_TIME).first())
    }

    @Test
    fun `undoing a mistaken tick takes its rewatch back`() = runTest {
        repository.setSeen(ep1, seen = true)
        clock.set(mar2026)
        repository.recordPlay(ep1)
        repository.removeLatestPlay(ep1)

        assertEquals(emptyMap(), repository.observeRewatchCounts(ALL_TIME).first())
    }

    @Test
    fun `timestamps report the rewatch moment, not the first viewing`() = runTest {
        repository.setSeen(ep1, seen = true) // December
        clock.set(mar2026)
        repository.recordPlay(ep1)

        assertEquals(listOf(mar2026), repository.observeRewatchTimestamps(ALL_TIME).first())
    }

    /**
     * Removing a title leaves its history alone (only `clearForMedia` erases
     * it), which is what lets the ranking drop it while the trend keeps
     * counting it — see ADR 0012.
     */
    @Test
    fun `clearing a title's progress removes its rewatches`() = runTest {
        repository.setSeen(ep1, seen = true)
        clock.set(mar2026)
        repository.recordPlay(ep1)
        repository.clearForMedia(show)

        assertEquals(emptyMap(), repository.observeRewatchCounts(ALL_TIME).first())
        assertEquals(emptyList(), repository.observeRewatchTimestamps(ALL_TIME).first())
    }

    private companion object {
        const val ALL_TIME = 0L
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
