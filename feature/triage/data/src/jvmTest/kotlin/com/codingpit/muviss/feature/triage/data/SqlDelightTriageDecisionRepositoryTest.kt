@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.data

import app.cash.turbine.test
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.feature.triage.domain.TriageDecision
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightTriageDecisionRepositoryTest {

    private lateinit var database: MuvissDatabase
    private lateinit var clock: FakeClock
    private lateinit var repository: SqlDelightTriageDecisionRepository

    private val matrix = MediaId.tmdbMovie("603")
    private val got = MediaId.tmdbTv("1399")

    @BeforeTest
    fun setUp() = runTest {
        database = inMemoryDatabase()
        clock = FakeClock(NOW_EPOCH_MS)
        repository = SqlDelightTriageDecisionRepository(
            database.triageDecisionQueries,
            ImmediateDispatchers(UnconfinedTestDispatcher()),
            clock,
        )
    }

    private fun decision(mediaId: MediaId, verdict: TriageVerdict, resolved: Boolean = true) = TriageDecision(
        mediaId = mediaId,
        verdict = verdict,
        title = "Title",
        posterUrl = null,
        decidedAtEpochMs = NOW_EPOCH_MS,
        resolved = resolved,
    )

    @Test
    fun `recording a decision makes it decided and dirty`() = runTest {
        repository.record(decision(matrix, TriageVerdict.SKIP))

        assertEquals(setOf(matrix), repository.observeDecidedIds().first())
        val row = database.triageDecisionQueries.selectById(matrix.toString()).executeAsOne()
        assertTrue(row.isDirty)
        assertEquals(NOW_EPOCH_MS, row.updatedAtEpochMs)
    }

    @Test
    fun `re-triaging overwrites the standing verdict in place`() = runTest {
        repository.record(decision(matrix, TriageVerdict.SKIP))
        clock.advanceBy(1_000)
        repository.record(decision(matrix, TriageVerdict.LATER))

        assertEquals(1, database.triageDecisionQueries.selectAll().executeAsList().size)
        assertEquals(TriageVerdict.LATER, repository.observeDecision(matrix).first()?.verdict)
    }

    @Test
    fun `restore soft-deletes so the title becomes deck-eligible again`() = runTest {
        repository.record(decision(matrix, TriageVerdict.SKIP))
        clock.advanceBy(1_000)

        repository.restore(matrix)

        assertEquals(emptySet(), repository.observeDecidedIds().first())
        assertNull(repository.observeDecision(matrix).first())
        // The row survives as a tombstone so the delete can propagate (ADR 0009).
        val row = database.triageDecisionQueries.selectById(matrix.toString()).executeAsOne()
        assertTrue(row.deleted)
        assertTrue(row.isDirty)
        assertEquals(NOW_EPOCH_MS + 1_000, row.updatedAtEpochMs)
    }

    @Test
    fun `re-triaging a restored title revives its row rather than staying hidden behind the tombstone`() = runTest {
        repository.record(decision(matrix, TriageVerdict.SKIP))
        repository.restore(matrix)

        repository.record(decision(matrix, TriageVerdict.CAUGHT_UP))

        assertEquals(setOf(matrix), repository.observeDecidedIds().first())
        assertEquals(TriageVerdict.CAUGHT_UP, repository.observeDecision(matrix).first()?.verdict)
    }

    @Test
    fun `skipped titles come back newest first`() = runTest {
        repository.record(decision(matrix, TriageVerdict.SKIP).copy(decidedAtEpochMs = 1_000))
        repository.record(decision(got, TriageVerdict.SKIP).copy(decidedAtEpochMs = 2_000))
        repository.record(decision(MediaId.tmdbMovie("1"), TriageVerdict.LATER))

        val skipped = repository.observeByVerdict(TriageVerdict.SKIP).first()

        assertEquals(listOf(got, matrix), skipped.map { it.mediaId })
    }

    @Test
    fun `unresolved returns only the decisions whose side effects never landed`() = runTest {
        repository.record(decision(matrix, TriageVerdict.LATER, resolved = false))
        repository.record(decision(got, TriageVerdict.SKIP, resolved = true))

        assertEquals(listOf(matrix), repository.unresolved().map { it.mediaId })

        repository.markResolved(matrix, resolved = true)
        assertEquals(emptyList(), repository.unresolved())
    }

    @Test
    fun `a verdict written by a newer build is dropped from reads but still counts as decided`() = runTest {
        // Conservative on purpose: an unreadable decision should keep a title
        // out of the deck, never put it back in.
        database.triageDecisionQueries.upsert(
            mediaId = matrix.toString(),
            mediaType = "movie",
            verdict = "SOME_FUTURE_VERDICT",
            title = "The Matrix",
            posterUrl = null,
            decidedAtEpochMs = NOW_EPOCH_MS,
            resolved = true,
            updatedAtEpochMs = NOW_EPOCH_MS,
            isDirty = false,
            deleted = false,
        )

        assertEquals(setOf(matrix), repository.observeDecidedIds().first())
        assertNull(repository.observeDecision(matrix).first())
    }

    @Test
    fun `decided ids emit reactively as decisions are written`() = runTest {
        repository.observeDecidedIds().test {
            assertEquals(emptySet(), awaitItem())

            repository.record(decision(matrix, TriageVerdict.SKIP))
            assertEquals(setOf(matrix), awaitItem())

            repository.restore(matrix)
            assertEquals(emptySet(), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
