package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.MediaType
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TmdbCacheTest {

    @Test
    fun a_repeated_call_within_the_servers_max_age_is_served_from_the_cache() = runTest {
        val fixture = ProviderFixture { respondJson(ONE_RESULT_PAGE) { append(HttpHeaders.CacheControl, "public, max-age=3600") } }

        val first = fixture.provider.search("matrix")
        val second = fixture.provider.search("matrix")

        assertEquals(first, second)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun a_response_without_cache_headers_is_not_cached() = runTest {
        val fixture = ProviderFixture { respondJson(ONE_RESULT_PAGE) }

        fixture.provider.search("matrix")
        fixture.provider.search("matrix")

        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun an_error_is_never_cached() = runTest {
        var calls = 0
        val fixture = ProviderFixture {
            if (++calls == 1) {
                respondJson("{}", io.ktor.http.HttpStatusCode.Unauthorized) { append(HttpHeaders.CacheControl, "public, max-age=3600") }
            } else {
                respondJson(ONE_RESULT_PAGE)
            }
        }

        runCatching { fixture.provider.search("matrix") }
        val page = fixture.provider.search("matrix")

        assertEquals(1, page.items.size)
    }

    @Test
    fun details_are_never_served_from_the_cache_so_a_refresh_sees_new_episodes() = runTest {
        val fixture = ProviderFixture {
            respondJson(tvShowJson(0)) { append(HttpHeaders.CacheControl, "public, max-age=28800") }
        }
        val show = com.codingpit.muviss.models.MediaId.tmdbTv("1399")

        fixture.provider.details(show)
        fixture.provider.details(show)

        assertEquals(2, fixture.requests.size)
    }

    // Reproduces the Search screen's "You appear to be offline" on a phone that was
    // online: Ktor's HttpCache revalidates a stale entry, the server answers 304,
    // and when the 304's Vary does not match what was stored Ktor cannot find the
    // entry and throws InvalidCacheStateException — every time, for the life of
    // the process. The call must still succeed.
    @Test
    fun a_304_whose_cache_entry_cannot_be_found_is_fetched_again_without_the_cache() = runTest {
        val fixture = ProviderFixture { request ->
            if (request.headers[HttpHeaders.IfNoneMatch] != null) {
                respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.Vary, "Accept-Language"))
            } else {
                respondJson(GENRES) {
                    append(HttpHeaders.ETag, "\"v1\"")
                    append(HttpHeaders.CacheControl, "public, max-age=0")
                }
            }
        }

        fixture.provider.genres(MediaType.MOVIE)
        val genres = fixture.provider.genres(MediaType.MOVIE)

        assertEquals(listOf("Action"), genres.map { it.name })
    }

    @Test
    fun a_304_whose_cache_entry_cannot_be_found_is_not_retried_against_the_same_entry() = runTest {
        val fixture = ProviderFixture { request ->
            if (request.headers[HttpHeaders.IfNoneMatch] != null) {
                respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.Vary, "Accept-Language"))
            } else {
                respondJson(GENRES) {
                    append(HttpHeaders.ETag, "\"v1\"")
                    append(HttpHeaders.CacheControl, "public, max-age=0")
                }
            }
        }

        fixture.provider.genres(MediaType.MOVIE)
        fixture.provider.genres(MediaType.MOVIE)

        // The first fetch, one revalidation that hits the missing entry, one uncached fetch.
        assertEquals(3, fixture.requests.size)
        assertEquals(emptyList(), fixture.delays)
    }
}

private const val GENRES = """{"genres":[{"id":28,"name":"Action"}]}"""
