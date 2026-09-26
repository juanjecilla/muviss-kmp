@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.widget.NoOpWidgetRefresher
import com.codingpit.muviss.core.database.MuvissDatabase
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
import com.codingpit.muviss.feature.triage.domain.UndoDecisionUseCase
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole flow, at the data layer, over one shared in-memory database:
 * a verdict → a decision row → a collection snapshot → episode ticks → the
 * *derived* `WatchStatus` the Library and Progress screens read.
 *
 * Modelled on collection's own `ProgressCollectionStatusIntegrationTest`, and
 * for the same reason: the promise triage makes ("caught up" really means
 * caught up) only holds if three slices agree, and nothing that mocks one of
 * them can prove it.
 */
class TriageCollectionProgressIntegrationTest {

    private lateinit var database: MuvissDatabase
    private lateinit var triageRepository: SqlDelightTriageDecisionRepository
    private lateinit var snoozeRepository: TriageSnoozeRepository
    private lateinit var deckClock: AppClock
    private lateinit var collectionApi: RealCollectionApi
    private lateinit var progressApi: RealProgressApi
    private lateinit var detailsSource: CountingDetailsSource
    private lateinit var record: RecordDecisionUseCase

    private val ongoing = showDetails(id = "1399", airedCount = 40, unairedCount = 10, productionStatus = ProductionStatus.RETURNING)
    private val ended = showDetails(id = "1396", airedCount = 40, productionStatus = ProductionStatus.ENDED)
    private val withSpecial = showDetails(id = "1400", airedCount = 5, includeUndatedSpecial = true)
    private val film = movieDetails()

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
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
        detailsSource = CountingDetailsSource(
            listOf(ongoing, ended, withSpecial, film).associateBy { it.id },
        )
        record = RecordDecisionUseCase(triageRepository, collectionApi, progressApi, detailsSource, clock)
    }

    private suspend fun statusOf(details: MediaDetails): WatchStatus? = collectionApi.observeSummaries().first().firstOrNull { it.mediaId == details.id }?.status

    @Test
    fun `caught up on an ended show ticks every aired episode and derives Finished`() = runTest {
        record(ended.summary, TriageVerdict.CAUGHT_UP).getOrThrow()

        assertEquals(1, database.triageDecisionQueries.selectAll().executeAsList().size)
        assertEquals(1, database.collectionEntryQueries.selectAll().executeAsList().size)
        assertEquals(40, database.episodeProgressQueries.selectForMedia(ended.id.toString()).executeAsList().count { it.seen })
        assertEquals(WatchStatus.FINISHED, statusOf(ended))
    }

    @Test
    fun `caught up on an ongoing show derives Watched, not Finished`() = runTest {
        record(ongoing.summary, TriageVerdict.CAUGHT_UP).getOrThrow()

        // Exactly the 40 aired episodes — never the 10 that haven't aired.
        assertEquals(40, database.episodeProgressQueries.selectForMedia(ongoing.id.toString()).executeAsList().count { it.seen })
        assertEquals(WatchStatus.WATCHED, statusOf(ongoing))
    }

    @Test
    fun `caught up never ticks an undated special, so status derivation cannot blow up`() = runTest {
        // The regression this guards: WatchProgress requires seen <= aired and
        // throws otherwise, so ticking a special with no air date would make
        // reading the library's own status crash.
        record(withSpecial.summary, TriageVerdict.CAUGHT_UP).getOrThrow()

        val ticked = database.episodeProgressQueries.selectForMedia(withSpecial.id.toString()).executeAsList()
        assertEquals(5, ticked.count { it.seen })
        assertTrue(ticked.none { it.seasonNumber == 0L && it.seen })
        assertEquals(WatchStatus.WATCHED, statusOf(withSpecial))
    }

    @Test
    fun `watching ticks one episode and derives Watching`() = runTest {
        record(ongoing.summary, TriageVerdict.WATCHING).getOrThrow()

        assertEquals(1, database.episodeProgressQueries.selectForMedia(ongoing.id.toString()).executeAsList().count { it.seen })
        assertEquals(WatchStatus.WATCHING, statusOf(ongoing))
    }

    @Test
    fun `later saves the title unticked and derives NotStarted`() = runTest {
        record(ongoing.summary, TriageVerdict.LATER).getOrThrow()

        assertEquals(0, database.episodeProgressQueries.selectForMedia(ongoing.id.toString()).executeAsList().count { it.seen })
        assertEquals(WatchStatus.NOT_STARTED, statusOf(ongoing))
    }

    @Test
    fun `caught up on a movie derives Watched via the synthetic movie tick`() = runTest {
        record(film.summary, TriageVerdict.CAUGHT_UP).getOrThrow()

        assertEquals(WatchStatus.WATCHED, statusOf(film))
    }

    @Test
    fun `skip records a decision and puts nothing in the collection`() = runTest {
        record(ended.summary, TriageVerdict.SKIP).getOrThrow()

        assertEquals(1, database.triageDecisionQueries.selectAll().executeAsList().size)
        assertEquals(emptyList(), database.collectionEntryQueries.selectAll().executeAsList())
        assertEquals(0, detailsSource.fetchCalls)
    }

    @Test
    fun `a decision survives removing the title from the collection`() = runTest {
        record(ended.summary, TriageVerdict.CAUGHT_UP).getOrThrow()

        collectionApi.remove(ended.id)

        // The point of ADR 0010: tidying the library does not put a title back
        // into the deck. The decision outlives the collection entry.
        assertEquals(setOf(ended.id), triageRepository.observeDecidedIds().first())
        assertNull(statusOf(ended))
    }

    @Test
    fun `undo reverses the save and the ticks so the title is deck-eligible again`() = runTest {
        record(ended.summary, TriageVerdict.CAUGHT_UP).getOrThrow()

        UndoDecisionUseCase(triageRepository, collectionApi, progressApi)(ended.id, TriageVerdict.CAUGHT_UP)

        assertEquals(emptySet(), triageRepository.observeDecidedIds().first())
        assertNull(statusOf(ended))
        // Ticks are cleared, not left behind marking a show nobody watched.
        assertEquals(0, database.episodeProgressQueries.selectForMedia(ended.id.toString()).executeAsList().count { it.seen })
    }

    @Test
    fun `the deck excludes both decided titles and pre-existing collection members`() = runTest {
        // A title saved from search long before triage existed: no decision
        // row, but it must never be offered.
        collectionApi.add(film)
        record(ended.summary, TriageVerdict.SKIP).getOrThrow()

        val source = FixedDeckSource(listOf(film.summary, ended.summary, ongoing.summary))
        val loadDeck = LoadDeckUseCase(DeckLoader(source), triageRepository, snoozeRepository, collectionApi, deckClock)

        val batch = loadDeck(DeckFilter(type = MediaType.TV), DeckCursor()).getOrThrow()

        assertEquals(listOf(ongoing.id), batch.cards.map { it.id })
    }

    @Test
    fun `a restored skip comes back around in the deck`() = runTest {
        record(ended.summary, TriageVerdict.SKIP).getOrThrow()
        triageRepository.restore(ended.id)

        val loadDeck = LoadDeckUseCase(DeckLoader(FixedDeckSource(listOf(ended.summary))), triageRepository, snoozeRepository, collectionApi, deckClock)

        assertEquals(listOf(ended.id), loadDeck(DeckFilter(type = MediaType.TV), DeckCursor()).getOrThrow().cards.map { it.id })
    }

    @Test
    fun `a failed commit still blocks the title from reappearing`() = runTest {
        val offline = CountingDetailsSource(emptyMap())
        val recordOffline = RecordDecisionUseCase(triageRepository, collectionApi, progressApi, offline, FakeClock())

        assertTrue(recordOffline(ongoing.summary, TriageVerdict.LATER).isFailure)

        assertEquals(setOf(ongoing.id), triageRepository.observeDecidedIds().first())
        assertEquals(listOf(ongoing.id), triageRepository.unresolved().map { it.mediaId })
        assertEquals(emptyList(), database.collectionEntryQueries.selectAll().executeAsList())
    }
}

/** Hands back one page of whatever it was given, for both media types. */
private class FixedDeckSource(private val items: List<MediaSummary>) : DeckSource {
    override suspend fun page(type: MediaType, page: Int, genreId: String?): Result<PagedResult<MediaSummary>> = Result.success(PagedResult(items.filter { it.type == type }, page = 1, totalPages = 1))

    override suspend fun genres(type: MediaType): Result<List<Genre>> = Result.success(emptyList())
}
