package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.network.redactCredentials
import com.codingpit.muviss.models.MediaId
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbCredentialsTest {

    private fun tvHandler(): io.ktor.client.engine.mock.MockRequestHandler = { request ->
        val append = request.url.parameters["append_to_response"].orEmpty()
        val path = request.url.encodedPath
        when {
            path.endsWith("/season/1") -> respondJson(seasonJson(1))
            append.startsWith("season/") -> respondJson(tvWithAppendedSeasons(append.split(",").map { it.removePrefix("season/").toInt() }))
            else -> respondJson(tvShowJson(seasonCount = 2))
        }
    }

    @Test
    fun a_read_token_travels_as_a_bearer_header_and_no_url_carries_a_key() = runTest {
        val fixture = ProviderFixture(credentials = TmdbCredentials(readToken = READ_TOKEN, apiKey = API_KEY), handler = tvHandler())

        fixture.provider.details(MediaId.tmdbTv("1399"))

        assertTrue(fixture.requests.isNotEmpty())
        fixture.requests.forEach { request ->
            assertEquals("Bearer $READ_TOKEN", request.headers[HttpHeaders.Authorization])
            assertNull(request.url.parameters["api_key"])
        }
        fixture.urls.forEach { url ->
            assertFalse(url.contains("api_key"), url)
            assertFalse(url.contains(API_KEY), url)
            assertFalse(url.contains(READ_TOKEN), url)
        }
    }

    @Test
    fun without_a_read_token_the_v3_key_is_the_api_key_parameter_and_there_is_no_authorization_header() = runTest {
        val fixture = ProviderFixture(credentials = TmdbCredentials(apiKey = API_KEY), handler = tvHandler())

        fixture.provider.details(MediaId.tmdbTv("1399"))

        fixture.requests.forEach { request ->
            assertEquals(API_KEY, request.url.parameters["api_key"])
            assertNull(request.headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun credentials_do_not_print_themselves() {
        assertFalse(TmdbCredentials(READ_TOKEN, API_KEY).toString().let { it.contains(API_KEY) || it.contains(READ_TOKEN) })
    }

    @Test
    fun redaction_scrubs_a_key_in_a_query_string_and_a_bearer_token() {
        val line = "GET https://api.themoviedb.org/3/search/multi?language=en&api_key=$API_KEY&page=1 Authorization: Bearer $READ_TOKEN"

        val scrubbed = redactCredentials(line)

        assertFalse(scrubbed.contains(API_KEY), scrubbed)
        assertFalse(scrubbed.contains(READ_TOKEN), scrubbed)
        assertTrue(scrubbed.contains("page=1"), "only the secret is removed: $scrubbed")
    }
}
