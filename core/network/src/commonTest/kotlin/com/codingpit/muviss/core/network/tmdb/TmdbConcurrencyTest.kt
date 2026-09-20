package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class TmdbConcurrencyTest {

    @Test
    fun a_burst_of_calls_never_has_more_than_the_limit_in_flight() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lock = Mutex()
        var inFlight = 0
        var peak = 0
        val fixture = ProviderFixture(maxConcurrentRequests = 4) {
            lock.withLock {
                inFlight++
                if (inFlight > peak) peak = inFlight
            }
            gate.await()
            lock.withLock { inFlight-- }
            respondJson(EMPTY_PAGE)
        }

        withContext(Dispatchers.Default) {
            val calls = (1..100).map { async { fixture.provider.search("q$it") } }
            // Wait until the limiter has let its quota through, then give any
            // excess a chance to show up before releasing the gate.
            withTimeout(10_000) { while (lock.withLock { inFlight } < 4) delay(5) }
            delay(100)
            assertEquals(4, lock.withLock { inFlight }, "exactly the limit is in flight while the gate is shut")
            gate.complete(Unit)
            calls.awaitAll()
        }

        assertEquals(4, peak)
        assertEquals(100, fixture.requests.size)
    }

    @Test
    fun the_limit_is_shared_by_every_call_type() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lock = Mutex()
        var inFlight = 0
        var peak = 0
        val fixture = ProviderFixture(maxConcurrentRequests = 2) { request ->
            lock.withLock {
                inFlight++
                if (inFlight > peak) peak = inFlight
            }
            gate.await()
            lock.withLock { inFlight-- }
            if (request.url.encodedPath.contains("/tv/")) respondJson(tvShowJson(0)) else respondJson(EMPTY_PAGE)
        }

        withContext(Dispatchers.Default) {
            val calls = (1..10).map { i ->
                async { if (i % 2 == 0) fixture.provider.details(MediaId.tmdbTv("1399")) else fixture.provider.trending() }
            }
            withTimeout(10_000) { while (lock.withLock { inFlight } < 2) delay(5) }
            delay(100)
            gate.complete(Unit)
            calls.awaitAll()
        }

        assertEquals(2, peak)
    }
}
