package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.common.secrets.InMemorySecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OwnTmdbKeyTest {

    private val v3 = "b".repeat(32)
    private val v4 = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJhYmMifQ.c2lnbmF0dXJl"
    private val store = InMemorySecretStore()
    private var verdict = KeyCheck.Accepted
    private val validated = mutableListOf<TmdbCredentials>()

    private fun ownKey() = OwnTmdbKey(store) { credentials ->
        validated += credentials
        verdict
    }

    @Test
    fun nothing_is_stored_by_default() = runTest {
        val key = ownKey()
        assertNull(key.credentials())
        assertEquals(TmdbKeySource.MUVISS, key.observeSource().first())
    }

    @Test
    fun an_accepted_key_is_kept_and_survives_a_restart() = runTest {
        assertEquals(SaveOwnKeyResult.Saved, ownKey().save("  $v3 "))
        assertEquals(v3, store.get(OwnTmdbKey.STORE_NAME), "stored trimmed")

        val restarted = ownKey()
        assertEquals(v3, restarted.credentials()?.apiKey)
        assertEquals(TmdbKeySource.YOURS, restarted.observeSource().first())
    }

    @Test
    fun a_v4_token_is_validated_as_a_bearer_credential() = runTest {
        ownKey().save(v4)
        assertEquals(v4, validated.single().readToken)
    }

    @Test
    fun a_key_of_neither_shape_is_not_sent_to_tmdb_at_all() = runTest {
        assertEquals(SaveOwnKeyResult.Malformed, ownKey().save("not a key"))
        assertEquals(emptyList(), validated)
        assertNull(store.get(OwnTmdbKey.STORE_NAME))
    }

    @Test
    fun a_rejected_key_is_not_saved_and_leaves_the_previous_one_in_place() = runTest {
        val key = ownKey()
        key.save(v3)
        verdict = KeyCheck.Rejected
        assertEquals(SaveOwnKeyResult.Rejected, key.save(v4))
        assertEquals(v3, key.credentials()?.apiKey)
        assertEquals(v3, store.get(OwnTmdbKey.STORE_NAME))
    }

    @Test
    fun a_key_that_could_not_be_checked_is_not_saved() = runTest {
        verdict = KeyCheck.Unknown
        assertEquals(SaveOwnKeyResult.CouldNotCheck, ownKey().save(v3))
        assertNull(store.get(OwnTmdbKey.STORE_NAME))
    }

    @Test
    fun clear_goes_back_to_muvisss_credential_and_forgets_the_secret() = runTest {
        val key = ownKey()
        key.save(v3)
        key.clear()
        assertNull(key.credentials())
        assertNull(store.get(OwnTmdbKey.STORE_NAME))
        assertEquals(TmdbKeySource.MUVISS, key.observeSource().first())
    }

    @Test
    fun the_http_validator_maps_tmdbs_answer_to_a_verdict() = runTest {
        suspend fun check(status: HttpStatusCode): KeyCheck = HttpTmdbKeyValidator(HttpClient(MockEngine { respond("{}", status) })).validate(TmdbCredentials(apiKey = v3))

        assertEquals(KeyCheck.Accepted, check(HttpStatusCode.OK))
        assertEquals(KeyCheck.Rejected, check(HttpStatusCode.Unauthorized))
        assertEquals(KeyCheck.Unknown, check(HttpStatusCode.TooManyRequests))
    }
}
