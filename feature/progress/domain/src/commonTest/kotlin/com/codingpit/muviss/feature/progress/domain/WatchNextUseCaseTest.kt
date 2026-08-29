@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.domain

import app.cash.turbine.test
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The watch-next join, tested where it now lives. These assertions were
 * `ProgressViewModelTest`'s until EPIC 22 moved the join into the domain so
 * the widgets could ask the same question; the ViewModel keeps only the tests
 * about being a ViewModel.
 */
class WatchNextUseCaseTest {

    private val show = MediaId.tmdbTv("1399")
    private val ep1 = EpisodeId(show, 1, 1)
    private val ep2 = EpisodeId(show, 1, 2)

    // "Today" is epoch day 100 (millis irrelevant beyond that division).
    private val today = 100L * 86_400_000L

    private val seasons = listOf(
        Season(1, "Season 1", listOf(testEpisode(show, 1, 1, 50), testEpisode(show, 1, 2, 60))),
    )

    private fun useCase(
        collectionApi: FakeCollectionApi,
        repository: FakeProgressRepository,
        catalogs: Map<MediaId, List<Season>> = mapOf(show to seasons),
        store: InMemoryEpisodeCatalogStore = InMemoryEpisodeCatalogStore(),
    ) = WatchNextUseCase(
        { collectionApi },
        ObserveSeenEpisodesUseCase(repository),
        EpisodeCatalogCache(FetchEpisodeCatalogUseCase(MapEpisodeCatalogSource(catalogs)), store),
        FixedClock(today),
    )

    @Test
    fun names_the_next_unseen_episode_of_a_watching_show() = runTest {
        val watchNext = useCase(FakeCollectionApi(listOf(collectionSummary(show))), FakeProgressRepository())

        watchNext().test {
            val item = awaitItem().singleOrNull() ?: awaitItem().single()
            assertEquals(show, item.mediaId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun ignores_titles_that_are_not_watching() = runTest {
        val notStarted = collectionSummary(MediaId.tmdbTv("2"), status = WatchStatus.NOT_STARTED)
        val watched = collectionSummary(MediaId.tmdbTv("3"), status = WatchStatus.WATCHED)
        val watchNext = useCase(FakeCollectionApi(listOf(notStarted, watched)), FakeProgressRepository())

        assertEquals(emptyList(), watchNext().first())
    }

    @Test
    fun advances_when_the_named_episode_is_ticked() = runTest {
        val repository = FakeProgressRepository()
        val watchNext = useCase(FakeCollectionApi(listOf(collectionSummary(show))), repository)

        watchNext().test {
            var item = awaitItem().firstOrNull()
            while (item?.nextEpisode == null) item = awaitItem().firstOrNull()
            assertEquals(ep1, item.nextEpisode?.id)

            repository.setSeen(ep1, true)

            var advanced = awaitItem().single()
            while (advanced.nextEpisode?.id == ep1) advanced = awaitItem().single()
            assertEquals(ep2, advanced.nextEpisode?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun counts_seen_against_aired_episodes_only() = runTest {
        val repository = FakeProgressRepository()
        val unaired = listOf(
            Season(
                1,
                "Season 1",
                listOf(testEpisode(show, 1, 1, 50), testEpisode(show, 1, 2, 60), testEpisode(show, 1, 3, 900)),
            ),
        )
        val watchNext = useCase(FakeCollectionApi(listOf(collectionSummary(show))), repository, mapOf(show to unaired))

        watchNext().test {
            var item = awaitItem().firstOrNull()
            while (item?.airedCount == 0) item = awaitItem().firstOrNull()
            assertEquals(2, item?.airedCount)
            assertEquals(0, item?.seenCount)
            assertNull(item?.progress?.takeIf { it != 0f })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun names_nothing_once_every_aired_episode_is_seen() = runTest {
        val repository = FakeProgressRepository()
        repository.setSeen(ep1, true)
        repository.setSeen(ep2, true)
        val watchNext = useCase(FakeCollectionApi(listOf(collectionSummary(show))), repository)

        watchNext().test {
            var item = awaitItem().firstOrNull()
            while (item == null || item.airedCount == 0) item = awaitItem().firstOrNull()
            assertNull(item.nextEpisode)
            assertEquals(2, item.seenCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The offline case a widget lives in: nothing has ever been fetched, but
     * the catalog is on disk, so the next episode still has a name.
     */
    @Test
    fun answers_from_the_store_with_no_source_available() = runTest {
        val repository = FakeProgressRepository()
        val store = InMemoryEpisodeCatalogStore(mapOf(show to seasons))
        val watchNext = useCase(
            FakeCollectionApi(listOf(collectionSummary(show))),
            repository,
            catalogs = emptyMap(),
            store = store,
        )

        watchNext().test {
            var item = awaitItem().firstOrNull()
            while (item?.nextEpisode == null) item = awaitItem().firstOrNull()
            assertEquals(ep1, item.nextEpisode?.id)
            assertTrue(store.savedIds.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A hanging provider must cost one row its episode name, never the whole
     * list — the shape of the never-ending spinner EPIC 20 had to fix.
     */
    @Test
    fun emits_rows_before_a_catalog_is_available() = runTest {
        val watchNext = useCase(
            FakeCollectionApi(listOf(collectionSummary(show, title = "Firefly"))),
            FakeProgressRepository(),
            catalogs = emptyMap(),
        )

        val first = watchNext().first()
        assertEquals(listOf("Firefly"), first.map { it.title })
        assertNull(first.single().nextEpisode)
    }
}
