package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.MediaId
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Operation-count budgets for the TV detail path, and the response shape it
 * relies on. The fixtures are built from TMDB's documented format
 * (`append_to_response=season/1,season/2` adds top-level `season/N` keys) —
 * not captured from the live API, which this suite never calls.
 */
class TmdbSeasonFetchTest {

    private fun appended(request: io.ktor.client.request.HttpRequestData): List<Int> = request.url.parameters["append_to_response"].orEmpty().split(",")
        .filter { it.startsWith("season/") }.map { it.removePrefix("season/").toInt() }

    private fun handler(seasonCount: Int, omit: Set<Int> = emptySet(), specials: Boolean = false): MockRequestHandler = { request ->
        val seasons = appended(request)
        val singleSeason = Regex("/tv/1399/season/(\\d+)$").find(request.url.encodedPath)?.groupValues?.get(1)?.toInt()
        when {
            singleSeason != null -> respondJson(seasonJson(singleSeason))
            seasons.isNotEmpty() -> respondJson(tvWithAppendedSeasons(seasons, omit))
            else -> respondJson(tvShowJson(seasonCount, specials))
        }
    }

    @Test
    fun a_15_season_show_costs_two_requests() = runTest {
        val fixture = ProviderFixture(handler = handler(15))

        val details = fixture.provider.details(MediaId.tmdbTv("1399"))

        assertEquals(2, fixture.requests.size)
        assertEquals((1..15).toList(), details.seasons.map { it.number })
        assertTrue(details.seasons.all { it.episodes.size == 2 })
    }

    @Test
    fun seasons_are_appended_in_chunks_of_twenty() = runTest {
        val fixture = ProviderFixture(handler = handler(45))

        val details = fixture.provider.details(MediaId.tmdbTv("1399"))

        // 1 show request + ceil(45 / 20) season requests.
        assertEquals(4, fixture.requests.size)
        assertEquals(listOf(20, 20, 5), fixture.requests.drop(1).map { appended(it).size })
        assertEquals(45, details.seasons.size)
        assertTrue(fixture.requests.all { appended(it).size <= TmdbProvider.MAX_APPENDED_SEASONS })
    }

    @Test
    fun season_zero_is_requested_only_when_listed() = runTest {
        val fixture = ProviderFixture(handler = handler(3, specials = true))

        val details = fixture.provider.details(MediaId.tmdbTv("1399"))

        // show + one request for the specials + one append for seasons 1..3.
        assertEquals(3, fixture.requests.size)
        assertTrue(fixture.requests.any { it.url.encodedPath.endsWith("/tv/1399/season/0") }, fixture.urls.toString())
        assertTrue(fixture.requests.flatMap(::appended).none { it == 0 }, "specials are never appended")
        assertEquals(listOf(0, 1, 2, 3), details.seasons.map { it.number })
    }

    @Test
    fun season_zero_is_not_requested_when_not_listed() = runTest {
        val fixture = ProviderFixture(handler = handler(3, specials = false))

        val details = fixture.provider.details(MediaId.tmdbTv("1399"))

        assertTrue(fixture.urls.none { it.contains("/season/0") }, fixture.urls.toString())
        assertEquals(listOf(1, 2, 3), details.seasons.map { it.number })
    }

    @Test
    fun a_failed_specials_request_fails_the_detail_load_like_any_season() = runTest {
        val fixture = ProviderFixture(
            handler = { request ->
                if (request.url.encodedPath.endsWith("/season/0")) {
                    respondJson("{}", HttpStatusCode.NotFound)
                } else {
                    respondJson(tvShowJson(2, specials = true))
                }
            },
        )

        val failure = runCatching { fixture.provider.details(MediaId.tmdbTv("1399")) }.exceptionOrNull()

        assertTrue(failure is com.codingpit.muviss.models.MetadataError, "was $failure")
    }

    @Test
    fun a_show_with_no_real_seasons_costs_one_request() = runTest {
        val fixture = ProviderFixture(handler = handler(0))

        val details = fixture.provider.details(MediaId.tmdbTv("1399"))

        assertEquals(1, fixture.requests.size)
        assertTrue(details.seasons.isEmpty())
    }

    @Test
    fun a_season_missing_from_the_appended_response_falls_back_to_its_own_request() = runTest {
        val fixture = ProviderFixture(handler = handler(seasonCount = 3, omit = setOf(2)))

        val details = fixture.provider.details(MediaId.tmdbTv("1399"))

        assertEquals(3, details.seasons.size, "no season is lost")
        assertEquals(3, fixture.requests.size, "show + one append + one fallback for season 2")
        assertTrue(fixture.urls.last().contains("/tv/1399/season/2"), fixture.urls.last())
    }

    @Test
    fun a_season_that_does_not_decode_falls_back_to_its_own_request() = runTest {
        val fixture = ProviderFixture(
            handler = { request ->
                val seasons = appended(request)
                when {
                    Regex("/season/\\d+$").containsMatchIn(request.url.encodedPath) -> respondJson(seasonJson(1))
                    seasons.isNotEmpty() -> respondJson("""{"id":1399,"name":"Show","season/1":{"unexpected":true}}""")
                    else -> respondJson(tvShowJson(1))
                }
            },
        )

        val details = fixture.provider.details(MediaId.tmdbTv("1399"))

        assertEquals(1, details.seasons.size)
        assertEquals(3, fixture.requests.size)
    }

    @Test
    fun an_error_on_the_appended_request_is_surfaced_not_swallowed() = runTest {
        val fixture = ProviderFixture(
            handler = { request ->
                if (appended(request).isNotEmpty()) respondJson("{}", HttpStatusCode.Unauthorized) else respondJson(tvShowJson(2))
            },
        )

        val failure = runCatching { fixture.provider.details(MediaId.tmdbTv("1399")) }.exceptionOrNull()

        assertTrue(failure is com.codingpit.muviss.models.MetadataError.Unauthorized, "was $failure")
    }
}
