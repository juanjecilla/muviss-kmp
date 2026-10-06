package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.MetadataError
import io.ktor.client.plugins.cache.InvalidCacheStateException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmdbFailureTraceTest {

    private suspend fun ProviderFixture.failSearch() {
        runCatching { provider.search("matrix") }
    }

    @Test
    fun a_cache_miss_is_traced_by_class_name_and_mapped_error() = runTest {
        val fixture = ProviderFixture { request -> throw InvalidCacheStateException(request.url) }

        fixture.failSearch()

        assertEquals(listOf("TMDB search/multi failed: InvalidCacheStateException -> Unknown"), fixture.traces)
    }

    @Test
    fun a_status_error_is_traced_through_the_response_exception() = runTest {
        val fixture = ProviderFixture { respondJson("{}", HttpStatusCode.Unauthorized) }

        fixture.failSearch()

        assertEquals(listOf("TMDB search/multi failed: ClientRequestException -> Unauthorized"), fixture.traces)
    }

    @Test
    fun a_trace_never_carries_the_message_url_or_key() = runTest {
        val fixture = ProviderFixture { throw IOException("Unable to resolve host api.themoviedb.org?api_key=$API_KEY") }

        fixture.failSearch()

        val line = fixture.traces.single()
        assertTrue(line.endsWith("-> Offline"), line)
        assertFalse(API_KEY in line, line)
        assertFalse("themoviedb" in line, line)
        assertFalse("Unable to resolve" in line, line)
    }

    @Test
    fun a_success_leaves_no_trace() = runTest {
        val fixture = ProviderFixture { respondJson("""{"page":1,"results":[],"total_pages":1}""") }

        fixture.provider.search("matrix")

        assertEquals(emptyList(), fixture.traces)
    }

    @Test
    fun the_line_follows_the_cause_chain_and_drops_any_query() {
        val failure = IllegalStateException("x", IOException("y"))

        val line = tmdbFailureLine("tv/1399?api_key=$API_KEY", failure, MetadataError.Offline())

        assertEquals("TMDB tv/1399 failed: IllegalStateException -> IOException -> Offline", line)
    }
}
