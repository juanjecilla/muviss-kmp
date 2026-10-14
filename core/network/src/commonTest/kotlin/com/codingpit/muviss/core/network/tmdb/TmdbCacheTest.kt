package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cache.InvalidCacheStateException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    // Guards the Search screen's "You appear to be offline" on a phone that was
    // online. Under Ktor 3.5 a 304 with a mismatched Vary threw
    // InvalidCacheStateException on every call. Ktor 3.6 tolerates that Vary
    // mismatch (see the test below), so this now passes on the cached entry; the
    // call must still succeed whichever path Ktor takes.
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

    // Ktor 3.6 rewrote HttpCache. A 304 whose Vary differs from the stored
    // response's no longer throws: the cache logs "Vary header mismatch" and
    // answers with the stored entry, so the revalidation succeeds on the cached
    // body. This pins that: two requests (the fetch and one conditional
    // revalidation), no uncached third fetch, no retry delay. Under Ktor 3.5
    // this exact sequence was a third request after InvalidCacheStateException.
    @Test
    fun a_304_whose_vary_differs_is_answered_from_the_cached_entry_without_a_refetch() = runTest {
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
        assertEquals(2, fixture.requests.size)
        assertEquals(emptyList(), fixture.delays)
    }

    // The fallback's real regression guard (#335). Ktor 3.6 no longer throws
    // InvalidCacheStateException for a Vary mismatch, and `HttpCache` offers no
    // seam to make it lose a stored entry, so the engine throws it instead: it
    // is the same exception on the same call, raised under the cached client.
    // The uncached client has no cache and so cannot raise it. Without the
    // retry in `TmdbProvider.request` this is the Search screen saying
    // "offline" on an online phone, until the process dies.
    @Test
    fun an_invalid_cache_state_is_answered_by_one_uncached_retry() = runTest {
        var thrown = false
        val fixture = ProviderFixture { request ->
            if (!thrown) {
                thrown = true
                throw InvalidCacheStateException(request.url)
            }
            respondJson(GENRES)
        }

        val genres = fixture.provider.genres(MediaType.MOVIE)

        assertEquals(listOf("Action"), genres.map { it.name })
        assertEquals(2, fixture.requests.size)
        assertEquals(emptyList(), fixture.delays)
    }

    @Test
    fun an_invalid_cache_state_that_the_uncached_retry_also_hits_is_unknown_not_offline() = runTest {
        val fixture = ProviderFixture { request -> throw InvalidCacheStateException(request.url) }

        assertFailsWith<MetadataError.Unknown> { fixture.provider.genres(MediaType.MOVIE) }

        assertEquals(2, fixture.requests.size)
    }
}

private const val GENRES = """{"genres":[{"id":28,"name":"Action"}]}"""
