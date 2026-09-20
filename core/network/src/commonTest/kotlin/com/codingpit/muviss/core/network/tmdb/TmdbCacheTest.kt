package com.codingpit.muviss.core.network.tmdb

import io.ktor.http.HttpHeaders
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
}
