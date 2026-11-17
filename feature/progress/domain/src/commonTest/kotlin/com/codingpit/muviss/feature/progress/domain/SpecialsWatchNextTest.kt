@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Watch Next counts specials only by the per-device choice (#938, ADR 0037), through the same `SpecialsCounting` the
 * Library reads. Its next episode never changes: a special is never "next".
 */
class SpecialsWatchNextTest {

    private val show = MediaId.tmdbTv("1399")
    private val today = 100L * 86_400_000L
    private val ep1 = EpisodeId(show, 1, 1)
    private val ep2 = EpisodeId(show, 1, 2)
    private val special = EpisodeId(show, 0, 1)

    private val regular = Season(1, "Season 1", listOf(testEpisode(show, 1, 1, 50), testEpisode(show, 1, 2, 60)))
    private val specials = Season(0, "Specials", listOf(testEpisode(show, 0, 1, 70)))

    /** Watch Next's row once its catalog has loaded, for a title that is watching with [seen] ticked. */
    private suspend fun row(catalog: List<Season>, seen: Set<EpisodeId>, countSpecials: Boolean): WatchNextItem {
        val repository = FakeProgressRepository()
        seen.forEach { repository.setSeen(it, true) }
        val watchNext = WatchNextUseCase(
            { FakeCollectionApi(listOf(collectionSummary(show))) },
            ObserveSeenEpisodesUseCase(repository),
            ObserveLastWatchedAtUseCase(repository),
            EpisodeCatalogCache(FetchEpisodeCatalogUseCase(MapEpisodeCatalogSource(mapOf(show to catalog))), InMemoryEpisodeCatalogStore()),
            FakeClock(today),
            flowOf(countSpecials),
        )
        return watchNext().first { rows -> rows.singleOrNull()?.catalogLoaded == true }.single()
    }

    @Test
    fun a_seen_special_is_not_counted_while_the_setting_is_off() = runTest {
        val item = row(listOf(regular, specials), setOf(ep1, special), countSpecials = false)

        assertEquals(1, item.seenCount)
        assertEquals(2, item.airedCount)
        assertEquals(ep2, item.nextEpisode?.id)
    }

    @Test
    fun a_seen_special_is_counted_when_the_setting_is_on() = runTest {
        val item = row(listOf(regular, specials), setOf(ep1, special), countSpecials = true)

        assertEquals(2, item.seenCount)
        assertEquals(3, item.airedCount)
        assertEquals(ep2, item.nextEpisode?.id, "a special is never next, so the next regular episode is still the answer")
    }

    @Test
    fun a_special_ticked_with_no_cached_catalog_for_it_is_never_counted_even_with_the_setting_on() = runTest {
        // An upgraded install's catalog has no specials until the app runs once: the tick must not inflate seen.
        val item = row(listOf(regular), setOf(ep1, special), countSpecials = true)

        assertEquals(1, item.seenCount)
        assertEquals(2, item.airedCount)
    }

    @Test
    fun an_announced_special_is_not_counted_as_aired_even_with_the_setting_on() = runTest {
        val future = Season(0, "Specials", listOf(testEpisode(show, 0, 1, today + 30)))
        val item = row(listOf(regular, future), setOf(ep1), countSpecials = true)

        assertEquals(2, item.airedCount)
    }
}
