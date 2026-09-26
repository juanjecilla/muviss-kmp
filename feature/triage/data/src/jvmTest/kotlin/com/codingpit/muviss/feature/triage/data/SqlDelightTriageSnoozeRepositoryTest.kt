@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.triage.domain.TriageSnooze
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EPIC 42, ADR 0023. The rules that bite here are the ones `episodePlay`
 * taught (ADR 0013): deletes are soft, and every read has to filter them out.
 */
class SqlDelightTriageSnoozeRepositoryTest {

    private lateinit var database: MuvissDatabase
    private lateinit var clock: FakeClock
    private lateinit var repository: SqlDelightTriageSnoozeRepository

    private val matrix = MediaId.tmdbMovie("603")
    private val got = MediaId.tmdbTv("1399")

    @BeforeTest
    fun setUp() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        database = MuvissDatabase(driver)
        clock = FakeClock()
        repository = SqlDelightTriageSnoozeRepository(
            database.triageSnoozeQueries,
            ImmediateDispatchers(UnconfinedTestDispatcher()),
            clock,
        )
    }

    private fun snooze(mediaId: MediaId, dueAtEpochDay: Long, title: String = "Title") = TriageSnooze(
        mediaId = mediaId,
        title = title,
        year = 1999,
        posterUrl = null,
        overview = "An overview.",
        snoozedAtEpochMs = 1_000L,
        dueAtEpochDay = dueAtEpochDay,
    )

    @Test
    fun `a snooze round-trips with its whole card snapshot`() = runTest {
        repository.snooze(snooze(matrix, dueAtEpochDay = 20_007L, title = "The Matrix"))

        val stored = repository.observeAll().first().single()
        assertEquals("The Matrix", stored.title)
        assertEquals(1999, stored.year)
        assertEquals("An overview.", stored.overview)
        assertEquals(20_007L, stored.dueAtEpochDay)
        // The snapshot is the point: it is what renders the card on the due
        // date without asking the provider again.
        assertEquals("The Matrix", stored.toSummary().title)
    }

    @Test
    fun `every write stamps the change log`() = runTest {
        repository.snooze(snooze(matrix, dueAtEpochDay = 20_007L))

        val row = database.triageSnoozeQueries.selectById(matrix.toString()).executeAsOne()
        assertTrue(row.isDirty)
        assertEquals(clock.nowEpochMs(), row.updatedAtEpochMs)
    }

    @Test
    fun `unsnoozing is soft, and every read filters the tombstone out`() = runTest {
        repository.snooze(snooze(matrix, dueAtEpochDay = 20_000L))
        repository.unsnooze(matrix)

        // Gone from all three reads...
        assertTrue(repository.observeAll().first().isEmpty())
        assertTrue(repository.observeSnoozedIds().first().isEmpty())
        assertTrue(repository.due(today = 99_999L).isEmpty())
        assertNull(repository.observeSnooze(matrix).first())

        // ...but still present as a tombstone, so it can propagate. A hard
        // delete has no timestamp left to compare and the other device would
        // simply push its copy back.
        val row = database.triageSnoozeQueries.selectById(matrix.toString()).executeAsOne()
        assertTrue(row.deleted)
        assertTrue(row.isDirty)
    }

    @Test
    fun `re-snoozing revives a tombstone rather than hiding behind it`() = runTest {
        repository.snooze(snooze(matrix, dueAtEpochDay = 20_000L))
        repository.unsnooze(matrix)
        repository.snooze(snooze(matrix, dueAtEpochDay = 20_090L))

        val stored = repository.observeAll().first().single()
        assertEquals(20_090L, stored.dueAtEpochDay)
        assertTrue(!database.triageSnoozeQueries.selectById(matrix.toString()).executeAsOne().deleted)
    }

    @Test
    fun `re-snoozing an existing title overwrites its due date in place`() = runTest {
        repository.snooze(snooze(matrix, dueAtEpochDay = 20_007L))
        repository.snooze(snooze(matrix, dueAtEpochDay = 20_030L))

        assertEquals(listOf(20_030L), repository.observeAll().first().map { it.dueAtEpochDay })
    }

    @Test
    fun `due returns only what has come round, oldest first`() = runTest {
        repository.snooze(snooze(got, dueAtEpochDay = 20_010L))
        repository.snooze(snooze(matrix, dueAtEpochDay = 20_005L))

        assertEquals(listOf(matrix), repository.due(today = 20_005L).map { it.mediaId })
        // Inclusive of the day itself: a Snooze is a date, not an instant.
        assertEquals(listOf(matrix, got), repository.due(today = 20_010L).map { it.mediaId })
    }

    @Test
    fun `the list is ordered by when each title comes back, soonest first`() = runTest {
        repository.snooze(snooze(got, dueAtEpochDay = 20_090L))
        repository.snooze(snooze(matrix, dueAtEpochDay = 20_007L))

        // Deliberately not newest-decision-first like the Skipped screen: what
        // matters about a Snooze is when it returns.
        assertEquals(listOf(matrix, got), repository.observeAll().first().map { it.mediaId })
    }

    @Test
    fun `a row whose media id will not parse is dropped from reads`() = runTest {
        database.triageSnoozeQueries.upsert(
            mediaId = "not-a-media-id",
            mediaType = "MOVIE",
            title = "Nonsense",
            year = null,
            posterUrl = null,
            overview = null,
            snoozedAtEpochMs = 1L,
            dueAtEpochDay = 1L,
            updatedAtEpochMs = 1L,
            isDirty = true,
            deleted = false,
        )

        // Permissive on purpose, and the opposite of a decision: an unreadable
        // postponement should let the title come back, not hide it forever.
        assertTrue(repository.observeAll().first().isEmpty())
        assertTrue(repository.due(today = 99_999L).isEmpty())
    }
}
