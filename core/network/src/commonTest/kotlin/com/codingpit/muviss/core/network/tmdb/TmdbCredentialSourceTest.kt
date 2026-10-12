package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmdbCredentialSourceTest {

    private val builtIn = TmdbCredentials(readToken = "built-in-token", apiKey = "built-in-key")

    /** A remote config whose stored copy is only readable after [awaitStored] — the cold-start shape. */
    private class ColdRemote(private val stored: RemoteValues) : RemoteConfig {
        var awaited = false
        override val values: StateFlow<RemoteValues> = MutableStateFlow(RemoteValues(byokEnabled = false))

        override suspend fun awaitStored(): RemoteValues {
            awaited = true
            return stored
        }

        override suspend fun refreshIfStale() = Unit
    }

    @Test
    fun built_in_is_used_when_no_remote_token_is_served() = runTest {
        val source = RemoteFirstTmdbCredentialSource(ColdRemote(RemoteValues(byokEnabled = false)), builtIn)
        assertEquals("built-in-token", source.current().readToken)
        assertEquals("built-in-key", source.current().apiKey)
    }

    @Test
    fun a_blank_remote_token_counts_as_absent() = runTest {
        val source = RemoteFirstTmdbCredentialSource(ColdRemote(RemoteValues(false, tmdbFallbackReadToken = "  ")), builtIn)
        assertEquals("built-in-token", source.current().readToken)
    }

    @Test
    fun a_remote_token_replaces_the_built_in_credential() = runTest {
        val source = RemoteFirstTmdbCredentialSource(ColdRemote(RemoteValues(false, tmdbFallbackReadToken = "rotated")), builtIn)
        val chosen = source.current()
        assertEquals("rotated", chosen.readToken)
        assertEquals("", chosen.apiKey, "the revoked built-in v3 key must not ride along")
    }

    @Test
    fun the_stored_copy_is_awaited_before_choosing() = runTest {
        val remote = ColdRemote(RemoteValues(false, tmdbFallbackReadToken = "rotated"))
        RemoteFirstTmdbCredentialSource(remote, builtIn).current()
        assertEquals(true, remote.awaited)
    }

    @Test
    fun a_rejected_remote_token_is_not_replaced_by_the_built_in_one() = runTest {
        val fixture = ProviderFixture(
            credentialSource = RemoteFirstTmdbCredentialSource(
                ColdRemote(RemoteValues(false, tmdbFallbackReadToken = "rotated")),
                builtIn,
            ),
            handler = { respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json")) },
        )
        runCatching { fixture.provider.trending() }
        assertEquals(1, fixture.requests.size, "a 401 must not trigger a second request under another credential")
        assertEquals("Bearer rotated", fixture.requests.single().headers[HttpHeaders.Authorization])
        assertNull(fixture.requests.single().url.parameters["api_key"])
    }
}
