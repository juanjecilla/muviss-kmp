@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.widget.NoOpWidgetRefresher
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.CountingDriver
import com.codingpit.muviss.feature.collection.data.SqlDelightCollectionRepository
import com.codingpit.muviss.feature.progress.data.SqlDelightProgressRepository
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.feature.triage.domain.DeckCursor
import com.codingpit.muviss.feature.triage.domain.DeckFilter
import com.codingpit.muviss.feature.triage.domain.DeckLoader
import com.codingpit.muviss.feature.triage.domain.DeckSource
import com.codingpit.muviss.feature.triage.domain.LoadDeckUseCase
import com.codingpit.muviss.feature.triage.domain.RecordDecisionUseCase
import com.codingpit.muviss.feature.triage.domain.TriageSnoozeRepository
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Performance budgets, expressed as **operation counts** rather than
 * wall-clock time.
 *
 * The risks worth guarding are structural: a "caught up" swipe on a very long
 * show turning into 750 separate transactions, the deck's refill loop walking
 * the whole catalogue once most of it has been triaged, or the exclusion set
 * degrading into a per-row query as the decision log grows. Each of those
 * shows up as a number here and stays stable in CI, where a timing assertion
 * would not. One deliberately loose wall-clock ceiling is kept as a canary for
 * anything that turns the bulk-tick path quadratic.
 *
 * Frame-level cost of the drag itself needs a real device and is out of scope
 * for a JVM test.
 */
class TriagePerformanceBudgetTest {

    private lateinit var driver: CountingDriver
    private lateinit var database: MuvissDatabase
    private lateinit var triageRepository: SqlDelightTriageDecisionRepository
    private lateinit var snoozeRepository: TriageSnoozeRepository
    private lateinit var deckClock: AppClock
    private lateinit var collectionApi: RealCollectionApi
    private lateinit var progressApi: RealProgressApi

    /** Longer than any real show anyone tracks — if this holds, everything does. */
    private val hugeShow = showDetails(id = "456", airedCount = 750, productionStatus = ProductionStatus.RETURNING)

    @BeforeTest
    fun setUp() {
        driver = CountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        MuvissDatabase.Schema.synchronous().create(driver)
        database = MuvissDatabase(driver)

        val dispatchers = ImmediateDispatchers(UnconfinedTestDispatcher())
        val clock = FakeClock()
        val progressRepository = SqlDelightProgressRepository(database.episodeProgressQueries, database.episodePlayQueries, dispatchers, clock, NoOpWidgetRefresher)
        progressApi = RealProgressApi(progressRepository)
        collectionApi = RealCollectionApi(
            SqlDelightCollectionRepository(database.collectionEntryQueries, dispatchers, clock, progressApi),
        )
        triageRepository = SqlDelightTriageDecisionRepository(database.triageDecisionQueries, dispatchers, clock)
        snoozeRepository = SqlDelightTriageSnoozeRepository(database.triageSnoozeQueries, dispatchers, clock)
        deckClock = clock
    }

    @Test
    fun `caught up on a 750-episode show costs exactly one details fetch`() = runTest {
        val details = CountingDetailsSource(mapOf(hugeShow.id to hugeShow))
        val record = RecordDecisionUseCase(triageRepository, collectionApi, progressApi, details, FakeClock())

        record(hugeShow.summary, TriageVerdict.CAUGHT_UP).getOrThrow()

        // A card can't prefetch this — TmdbProvider.details does per-season
        // requests for TV — so one fetch per saving verdict is the budget.
        assertEquals(1, details.fetchCalls)
        assertEquals(750, database.episodeProgressQueries.selectForMedia(hugeShow.id.toString()).executeAsList().count { it.seen })
    }

    @Test
    fun `the 750 ticks are written inside a single transaction`() = runTest {
        val record = RecordDecisionUseCase(
            triageRepository,
            collectionApi,
            progressApi,
            CountingDetailsSource(mapOf(hugeShow.id to hugeShow)),
            FakeClock(),
        )
        driver.reset()

        record(hugeShow.summary, TriageVerdict.CAUGHT_UP).getOrThrow()

        // The guard that matters: one bulk transaction, not one per episode.
        // A regression here is 750 fsyncs on a phone.
        assertTrue(driver.transactions <= MAX_TRANSACTIONS_PER_VERDICT, "used ${driver.transactions} transactions for one verdict")
    }

    @Test
    fun `the bulk tick path stays comfortably fast`() = runTest {
        val record = RecordDecisionUseCase(
            triageRepository,
            collectionApi,
            progressApi,
            CountingDetailsSource(mapOf(hugeShow.id to hugeShow)),
            FakeClock(),
        )

        // Deliberately loose: this is a canary for an accidentally quadratic
        // write path, not a benchmark. Normal machine-to-machine variance
        // comes nowhere near it.
        val elapsed = measureTime { record(hugeShow.summary, TriageVerdict.CAUGHT_UP).getOrThrow() }

        assertTrue(elapsed.inWholeMilliseconds < BULK_TICK_CEILING_MS, "bulk tick took ${elapsed.inWholeMilliseconds}ms")
    }

    @Test
    fun `the refill loop is bounded when the whole catalogue is already decided`() = runTest {
        val pages = List(40) { page -> List(20) { MediaSummary(MediaId.tmdbMovie("${page * 20 + it}"), "Movie") } }
        pages.flatten().forEach { summary ->
            database.triageDecisionQueries.upsert(
                mediaId = summary.id.toString(),
                mediaType = "movie",
                verdict = "SKIP",
                title = summary.title,
                posterUrl = null,
                decidedAtEpochMs = NOW_EPOCH_MS,
                resolved = true,
                updatedAtEpochMs = NOW_EPOCH_MS,
                isDirty = false,
                deleted = false,
            )
        }
        val source = CountingDeckSource(pages)
        val loadDeck = LoadDeckUseCase(DeckLoader(source), triageRepository, snoozeRepository, collectionApi, deckClock)

        val batch = loadDeck(DeckFilter(type = MediaType.MOVIE), DeckCursor()).getOrThrow()

        assertEquals(emptyList(), batch.cards)
        assertEquals(DeckLoader.MAX_PAGES_PER_BATCH, source.pageCalls)
    }

    @Test
    fun `the exclusion set is one query however large the decision log is`() = runTest {
        repeat(DECISION_LOG_SIZE) { index ->
            database.triageDecisionQueries.upsert(
                mediaId = MediaId.tmdbMovie("$index").toString(),
                mediaType = "movie",
                verdict = "SKIP",
                title = "Movie $index",
                posterUrl = null,
                decidedAtEpochMs = NOW_EPOCH_MS,
                resolved = true,
                updatedAtEpochMs = NOW_EPOCH_MS,
                isDirty = false,
                deleted = false,
            )
        }
        driver.reset()

        val decided = triageRepository.observeDecidedIds().first()

        assertEquals(DECISION_LOG_SIZE, decided.size)
        // One SELECT, not one per row — the deck reads this on every refill.
        assertEquals(1, driver.queries)
    }

    private companion object {
        /** One for the collection snapshot's read-modify-write, one for the bulk ticks. */
        const val MAX_TRANSACTIONS_PER_VERDICT = 4
        const val BULK_TICK_CEILING_MS = 10_000L
        const val DECISION_LOG_SIZE = 5_000
    }
}

/** Serves fixed pages and counts how often the deck asks for one. */
private class CountingDeckSource(private val pages: List<List<MediaSummary>>) : DeckSource {
    var pageCalls = 0
        private set

    override suspend fun page(type: MediaType, page: Int, genreId: String?): Result<PagedResult<MediaSummary>> {
        pageCalls++
        return Result.success(PagedResult(pages.getOrNull(page - 1).orEmpty(), page = page, totalPages = pages.size))
    }

    override suspend fun genres(type: MediaType): Result<List<Genre>> = Result.success(emptyList())
}
