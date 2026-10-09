@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.widget.AppWidgets
import com.codingpit.muviss.core.common.widget.NoOpWidgetRefresher
import com.codingpit.muviss.core.common.widget.WidgetRefresher
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class RefreshDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

private class CountingRefresher : WidgetRefresher {
    var refreshes = 0
        private set

    override suspend fun refresh() {
        refreshes++
    }
}

/**
 * Every progress write tells the home-screen widgets (EPIC 22).
 *
 * The risk this guards is silent: a widget that misses a refresh does not
 * break, it just keeps naming an episode the person already ticked, and
 * nothing in the app looks wrong. Asserting per-mutation is the only way that
 * stays true when someone adds a ninth write next to the eight below.
 */
class WidgetRefreshOnWriteTest {

    private lateinit var refresher: CountingRefresher
    private lateinit var repository: SqlDelightProgressRepository

    private val show = MediaId.tmdbTv("1399")
    private val ep1 = EpisodeId(show, 1, 1)
    private val ep2 = EpisodeId(show, 1, 2)

    @BeforeTest
    fun setUp() {
        val database = inMemoryDatabase()
        refresher = CountingRefresher()
        repository = SqlDelightProgressRepository(
            database.episodeProgressQueries,
            database.episodePlayQueries,
            RefreshDispatchers(UnconfinedTestDispatcher()),
            FakeClock(1_000L),
            refresher,
        )
    }

    @AfterTest
    fun tearDown() = AppWidgets.reset()

    @Test
    fun `a tick refreshes the widgets`() = runTest {
        repository.setSeen(ep1, seen = true)

        assertEquals(1, refresher.refreshes)
    }

    @Test
    fun `un-ticking refreshes them too`() = runTest {
        repository.setSeen(ep1, seen = true)
        repository.setSeen(ep1, seen = false)

        assertEquals(2, refresher.refreshes)
    }

    @Test
    fun `a bulk mark refreshes once, not once per episode`() = runTest {
        repository.setSeenBulk(listOf(ep1, ep2), seen = true)

        assertEquals(1, refresher.refreshes, "the widget cares that the list changed, not how many rows moved")
    }

    @Test
    fun `every mutation on the repository refreshes`() = runTest {
        repository.setSeen(ep1, seen = true)
        repository.setSeenBulk(listOf(ep2), seen = true)
        repository.recordPlay(ep1)
        repository.recordPlaysForUnseen(listOf(ep1, ep2))
        repository.removeLatestPlay(ep1)
        repository.removeLatestPlays(listOf(ep2))
        repository.clearPlays(ep1)
        repository.clearForMedia(show)

        assertEquals(8, refresher.refreshes)
    }

    /**
     * The four targets with no home screen never install anything, and the
     * two that do install late — after the Koin graph already handed this
     * repository a refresher. Resolving the delegate per call is what makes
     * that ordering a non-issue.
     */
    @Test
    fun `AppWidgets delegates to whatever was installed, whenever it was installed`() = runTest {
        val installedLater = CountingRefresher()
        val viaSeam = AppWidgets

        viaSeam.refresh()
        AppWidgets.install(installedLater)
        viaSeam.refresh()

        assertEquals(1, installedLater.refreshes, "the seam was resolved before install and still routed the later call")

        AppWidgets.reset()
        viaSeam.refresh()
        assertEquals(1, installedLater.refreshes)
        assertEquals(Unit, NoOpWidgetRefresher.refresh())
    }
}
