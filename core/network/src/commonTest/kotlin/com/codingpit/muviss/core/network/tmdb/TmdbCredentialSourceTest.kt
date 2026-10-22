package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import com.codingpit.muviss.core.common.secrets.InMemorySecretStore
import com.codingpit.muviss.models.MetadataError
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The TMDB Credential Chain (ADR 0027): user, then remote, then built-in; skip only what is absent. */
class TmdbCredentialSourceTest {

    private val builtIn = TmdbCredentials(readToken = "built-in-token", apiKey = "built-in-key")
    private val userKey = "a".repeat(32)

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

    private fun remote(token: String? = null) = ColdRemote(RemoteValues(byokEnabled = false, tmdbFallbackReadToken = token))

    private suspend fun ownKeyHolding(raw: String?): OwnTmdbKey {
        val store = InMemorySecretStore()
        if (raw != null) store.put(OwnTmdbKey.STORE_NAME, raw)
        return OwnTmdbKey(store) { KeyCheck.Accepted }
    }

    @Test
    fun built_in_is_used_when_nothing_else_is_present() = runTest {
        val chosen = ChainedTmdbCredentialSource(null, remote(), builtIn).current()
        assertEquals(TmdbCredentialOrigin.BuiltIn, chosen.origin)
        assertEquals("built-in-token", chosen.credentials.readToken)
        assertEquals("built-in-key", chosen.credentials.apiKey)
    }

    @Test
    fun a_blank_remote_token_counts_as_absent() = runTest {
        val chosen = ChainedTmdbCredentialSource(null, remote("  "), builtIn).current()
        assertEquals(TmdbCredentialOrigin.BuiltIn, chosen.origin)
    }

    @Test
    fun a_remote_token_replaces_the_built_in_credential() = runTest {
        val chosen = ChainedTmdbCredentialSource(null, remote("rotated"), builtIn).current()
        assertEquals(TmdbCredentialOrigin.Remote, chosen.origin)
        assertEquals("rotated", chosen.credentials.readToken)
        assertEquals("", chosen.credentials.apiKey, "the revoked built-in v3 key must not ride along")
    }

    @Test
    fun the_stored_remote_copy_is_awaited_before_choosing() = runTest {
        val remote = remote("rotated")
        ChainedTmdbCredentialSource(null, remote, builtIn).current()
        assertEquals(true, remote.awaited)
    }

    @Test
    fun the_users_own_key_beats_both_remote_and_built_in() = runTest {
        val chosen = ChainedTmdbCredentialSource(ownKeyHolding(userKey), remote("rotated"), builtIn).current()
        assertEquals(TmdbCredentialOrigin.User, chosen.origin)
        assertEquals(userKey, chosen.credentials.apiKey)
        assertEquals("", chosen.credentials.readToken)
    }

    @Test
    fun an_unreadable_stored_value_counts_as_no_key_and_is_never_sent() = runTest {
        val chosen = ChainedTmdbCredentialSource(ownKeyHolding("not a key"), remote(), builtIn).current()
        assertEquals(TmdbCredentialOrigin.BuiltIn, chosen.origin)
    }

    @Test
    fun a_rejected_remote_token_is_not_replaced_by_the_built_in_one() = runTest {
        val fixture = ProviderFixture(
            credentialSource = ChainedTmdbCredentialSource(null, remote("rotated"), builtIn),
            handler = { respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json")) },
        )
        assertFailsWith<MetadataError.Unauthorized> { fixture.provider.trending() }
        assertEquals(1, fixture.requests.size, "a 401 must not trigger a second request under another credential")
        assertEquals("Bearer rotated", fixture.requests.single().headers[HttpHeaders.Authorization])
        assertNull(fixture.requests.single().url.parameters["api_key"])
    }

    @Test
    fun a_rejected_own_key_is_reported_as_the_users_and_never_retried_under_ours() = runTest {
        val fixture = ProviderFixture(
            credentialSource = ChainedTmdbCredentialSource(ownKeyHolding(userKey), remote("rotated"), builtIn),
            handler = { respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json")) },
        )
        val failure = assertFailsWith<MetadataError> { fixture.provider.trending() }
        assertIs<MetadataError.OwnKeyRejected>(failure)
        assertEquals(1, fixture.requests.size)
        assertEquals(userKey, fixture.requests.single().url.parameters["api_key"])
        assertNull(fixture.requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun changing_the_key_applies_to_the_next_request_without_a_restart() = runTest {
        val ownKey = ownKeyHolding(null)
        val fixture = ProviderFixture(
            credentialSource = ChainedTmdbCredentialSource(ownKey, remote(), builtIn),
            handler = { respond("""{"results":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
        )
        fixture.provider.trending()
        assertEquals("Bearer built-in-token", fixture.requests.last().headers[HttpHeaders.Authorization])

        assertEquals(SaveOwnKeyResult.Saved, ownKey.save(userKey))
        fixture.provider.trending()
        assertEquals(userKey, fixture.requests.last().url.parameters["api_key"])

        ownKey.clear()
        fixture.provider.trending()
        assertEquals("Bearer built-in-token", fixture.requests.last().headers[HttpHeaders.Authorization])
    }
}
