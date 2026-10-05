package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MetadataError
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.cache.InvalidCacheStateException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class TmdbProviderErrorTest {

    private suspend fun ProviderFixture.searchFailure(): MetadataError {
        try {
            provider.search("matrix")
        } catch (error: MetadataError) {
            return error
        }
        fail("expected search to fail")
    }

    @Test
    fun rate_limited_after_retries_surfaces_RateLimited_with_the_retry_after() = runTest {
        val fixture = ProviderFixture { respondJson("{}", HttpStatusCode.TooManyRequests) { append(HttpHeaders.RetryAfter, "3") } }

        val error = fixture.searchFailure()

        assertEquals(3, assertIs<MetadataError.RateLimited>(error).retryAfterSeconds)
        assertEquals(3, fixture.requests.size, "three attempts in total")
    }

    @Test
    fun retries_honour_retry_after_when_it_exceeds_the_backoff() = runTest {
        val fixture = ProviderFixture { respondJson("{}", HttpStatusCode.TooManyRequests) { append(HttpHeaders.RetryAfter, "7") } }

        fixture.searchFailure()

        assertEquals(2, fixture.delays.size)
        assertTrue(fixture.delays.all { it >= 7_000 }, "waited at least Retry-After: ${fixture.delays}")
    }

    @Test
    fun a_retry_after_too_long_to_wait_out_is_not_retried() = runTest {
        val fixture = ProviderFixture { respondJson("{}", HttpStatusCode.TooManyRequests) { append(HttpHeaders.RetryAfter, "3600") } }

        val error = fixture.searchFailure()

        assertEquals(3600, assertIs<MetadataError.RateLimited>(error).retryAfterSeconds)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun rate_limit_without_retry_after_has_a_null_retry_after() = runTest {
        val fixture = ProviderFixture { respondJson("{}", HttpStatusCode.TooManyRequests) }

        assertNull(assertIs<MetadataError.RateLimited>(fixture.searchFailure()).retryAfterSeconds)
    }

    @Test
    fun a_rate_limit_that_clears_is_invisible_to_the_caller() = runTest {
        var calls = 0
        val fixture = ProviderFixture {
            if (++calls == 1) respondJson("{}", HttpStatusCode.TooManyRequests) else respondJson(ONE_RESULT_PAGE)
        }

        val page = fixture.provider.search("matrix")

        assertEquals(1, page.items.size)
        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun server_errors_are_retried_then_reported_as_Unknown() = runTest {
        val fixture = ProviderFixture { respondJson("{}", HttpStatusCode.ServiceUnavailable) }

        assertIs<MetadataError.Unknown>(fixture.searchFailure())
        assertEquals(3, fixture.requests.size)
    }

    @Test
    fun unauthorized_is_Unauthorized_and_not_retried() = runTest {
        val fixture = ProviderFixture {
            respondJson("""{"status_code":7,"status_message":"Invalid API key"}""", HttpStatusCode.Unauthorized)
        }

        assertIs<MetadataError.Unauthorized>(fixture.searchFailure())
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun not_found_is_NotFound_on_a_detail_call_and_not_retried() = runTest {
        val fixture = ProviderFixture { respondJson("""{"status_code":34}""", HttpStatusCode.NotFound) }

        val error = try {
            fixture.provider.details(MediaId.tmdbMovie("1"))
            fail("expected failure")
        } catch (e: MetadataError) {
            e
        }

        assertIs<MetadataError.NotFound>(error)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun an_error_status_is_an_error_not_an_empty_page() = runTest {
        // TmdbPageDto defaults every field, so before EPIC 27 this body decoded to "no results".
        val fixture = ProviderFixture { respondJson("""{"status_message":"Internal error"}""", HttpStatusCode.InternalServerError) }

        assertIs<MetadataError>(fixture.searchFailure())
    }

    @Test
    fun a_cache_that_cannot_find_its_entry_is_Unknown_not_Offline() = runTest {
        // The request did reach TMDB; telling the user to check their connection was a lie.
        val fixture = ProviderFixture { request -> throw InvalidCacheStateException(request.url) }

        assertIs<MetadataError.Unknown>(fixture.searchFailure())
    }

    @Test
    fun an_empty_success_body_is_an_error_not_an_empty_page() = runTest {
        val fixture = ProviderFixture { respondJson("") }

        assertIs<MetadataError.Unknown>(fixture.searchFailure())
    }

    @Test
    fun a_body_that_is_not_json_is_an_error() = runTest {
        val fixture = ProviderFixture { respondJson("<html>bad gateway</html>") }

        assertIs<MetadataError.Unknown>(fixture.searchFailure())
    }

    @Test
    fun a_detail_body_missing_required_fields_is_Unknown_not_a_raw_serialization_message() = runTest {
        val fixture = ProviderFixture { respondJson("{}") }

        val error = try {
            fixture.provider.details(MediaId.tmdbMovie("1"))
            fail("expected failure")
        } catch (e: MetadataError) {
            e
        }

        assertIs<MetadataError.Unknown>(error)
        assertFalse(error.message.orEmpty().contains("MissingField", ignoreCase = true))
    }

    @Test
    fun a_request_timeout_is_Offline_and_its_url_never_reaches_the_message() = runTest {
        val fixture = ProviderFixture {
            throw HttpRequestTimeoutException("https://api.themoviedb.org/3/search/multi?api_key=$API_KEY", 30_000)
        }

        val error = fixture.searchFailure()

        assertIs<MetadataError.Offline>(error)
        assertNoSecrets(error)
        assertEquals(1, fixture.requests.size, "a spent request budget is not retried")
    }

    @Test
    fun a_connection_failure_is_retried_then_Offline() = runTest {
        val fixture = ProviderFixture { throw IOException("Unable to resolve host api.themoviedb.org?api_key=$API_KEY") }

        val error = fixture.searchFailure()

        assertIs<MetadataError.Offline>(error)
        assertNoSecrets(error)
        assertEquals(3, fixture.requests.size)
    }

    @Test
    fun a_platform_exception_of_unknown_type_is_Offline_with_a_fixed_message() = runTest {
        val fixture = ProviderFixture { error("NSURLErrorDomain -1009 for https://x?api_key=$API_KEY") }

        val error = fixture.searchFailure()

        assertIs<MetadataError.Offline>(error)
        assertNoSecrets(error)
    }

    @Test
    fun every_error_type_carries_fixed_text_and_no_cause() {
        val all = listOf(
            MetadataError.RateLimited(5),
            MetadataError.Unauthorized(),
            MetadataError.NotFound(),
            MetadataError.Offline(),
            MetadataError.Unknown(),
        )

        all.forEach { error ->
            assertNull(error.cause)
            assertTrue(error.userMessage.isNotBlank())
            assertFalse(error.message.orEmpty().contains("http", ignoreCase = true))
        }
    }

    @Test
    fun a_cancellation_is_not_swallowed_into_an_error() = runTest {
        val fixture = ProviderFixture { throw kotlinx.coroutines.CancellationException("cancelled") }

        val thrown = try {
            fixture.provider.search("x")
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue(thrown is kotlinx.coroutines.CancellationException, "was $thrown")
    }

    private fun assertNoSecrets(error: Throwable) {
        val text = generateSequence(error) { it.cause }.joinToString(" ") { "${it.message} ${it::class.simpleName}" }
        assertFalse(text.contains(API_KEY), text)
        assertFalse(text.contains("api_key", ignoreCase = true), text)
        assertFalse(text.contains("themoviedb"), text)
    }
}
