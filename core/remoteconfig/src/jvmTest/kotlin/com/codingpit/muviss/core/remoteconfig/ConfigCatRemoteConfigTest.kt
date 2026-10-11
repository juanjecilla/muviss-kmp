package com.codingpit.muviss.core.remoteconfig

import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.configcat.ConfigCatClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real ConfigCat SDK against a [MockEngine], so what is checked is
 * what ships: the wire format it parses, the cache it writes through, and how
 * many requests a day of foregrounding makes. No network.
 */
class ConfigCatRemoteConfigTest {

    private val defaults = RemoteValues(byokEnabled = false)
    private var requests = 0
    private var body: String = served(byok = true, token = "rotated-token")
    private var status = HttpStatusCode.OK

    private fun served(byok: Boolean, token: String): String = """
        {"p":{"u":"https://cdn-eu.configcat.com","r":0,"s":"salt"},
         "f":{"byokEnabled":{"t":0,"v":{"b":$byok}},
              "tmdbFallbackReadToken":{"t":1,"v":{"s":"$token"}}}}
    """.trimIndent()

    private val engine = MockEngine {
        requests++
        respond(
            body,
            status,
            Headers.build {
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.ETag, "\"v$requests\"")
            },
        )
    }

    // The SDK keeps one client per SDK key per process, so each test gets its own key.
    // Its shape is validated: `configcat-sdk-1/` then two 22-character segments.
    private var keyCounter = 0
    private fun sdkKey() = "configcat-sdk-1/${"k${++keyCounter}x${System.nanoTime()}".padEnd(22, 'a').take(22)}/aaaaaaaaaaaaaaaaaaaaaa"

    private fun remote(key: String, db: MuvissDatabase, clock: FakeClock) = ConfigCatRemoteConfig(
        sdkKey = key,
        engine = engine,
        cache = SqlDelightConfigCache(db.remoteConfigCacheQueries),
        defaults = defaults,
        clock = clock,
    )

    @Test
    fun nothing_is_fetched_until_a_refresh_is_asked_for() = runTest {
        val config = remote(sdkKey(), inMemoryDatabase(), FakeClock(0))
        assertEquals(defaults, config.awaitStored())
        assertEquals(0, requests)
    }

    @Test
    fun a_refresh_serves_the_remote_values() = runTest {
        val config = remote(sdkKey(), inMemoryDatabase(), FakeClock(0))
        config.refreshIfStale()
        assertEquals(RemoteValues(byokEnabled = true, tmdbFallbackReadToken = "rotated-token"), config.values.value)
    }

    @Test
    fun a_second_refresh_inside_the_hour_makes_no_request() = runTest {
        val clock = FakeClock(0)
        val config = remote(sdkKey(), inMemoryDatabase(), clock)
        config.refreshIfStale()
        clock.epochMs = 59 * 60 * 1000L
        config.refreshIfStale()
        assertEquals(1, requests)
        clock.epochMs = 61 * 60 * 1000L
        config.refreshIfStale()
        assertEquals(2, requests)
    }

    @Test
    fun the_last_good_config_survives_a_restart_without_the_network() = runTest {
        val db = inMemoryDatabase()
        val key = sdkKey()
        remote(key, db, FakeClock(0)).refreshIfStale()
        val requestsBefore = requests

        // A new process: the SDK's cache entry is keyed by the SDK key, so the key stays; the
        // client (kept per key per process) is closed, the database stays, and the engine is down.
        ConfigCatClient.closeAll()
        status = HttpStatusCode.InternalServerError
        val restarted = remote(key, db, FakeClock(0))
        val stored = restarted.awaitStored()

        assertEquals("rotated-token", stored.tmdbFallbackReadToken)
        assertEquals(true, stored.byokEnabled)
        assertEquals(requestsBefore, requests, "reading the stored copy must not touch the network")
    }

    @Test
    fun a_failed_fetch_keeps_the_defaults_and_does_not_throw() = runTest {
        status = HttpStatusCode.InternalServerError
        val config = remote(sdkKey(), inMemoryDatabase(), FakeClock(0))
        config.refreshIfStale()
        assertEquals(defaults, config.values.value)
        assertNull(config.values.value.tmdbFallbackReadToken)
    }

    @Test
    fun an_empty_served_token_means_use_the_built_in_one() = runTest {
        body = served(byok = false, token = "")
        val config = remote(sdkKey(), inMemoryDatabase(), FakeClock(0))
        config.refreshIfStale()
        assertNull(config.values.value.tmdbFallbackReadToken)
        assertTrue(requests >= 1)
    }
}
