package com.codingpit.muviss.oauth

import com.codingpit.muviss.core.sync.SignInFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The loopback server briefly holds a single-use authorization code, which is
 * reason enough not to ship it on a "it worked when I tried it once".
 *
 * These bind real sockets rather than faking `HttpServer`. The whole point of
 * the class is what happens at the socket — a port already taken, a request to
 * the wrong path — and a fake would only assert the parts that were never in
 * doubt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopbackRedirectServerTest {

    private val opened = mutableListOf<AutoCloseable>()

    /**
     * A *real* scope for every test that actually speaks HTTP to the server.
     *
     * Handing it `runTest`'s TestScope instead looks tidier and is wrong: the
     * timeout is a `delay` inside that scope, so on virtual time it elapses the
     * moment the test body idles — which is exactly what awaiting a blocking
     * socket read does. The server released its port before the request
     * arrived, and all three request tests failed with `Connection refused`.
     * Only [an abandoned sign-in] wants virtual time, and it passes the
     * TestScope deliberately.
     */
    private val realScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        realScope.cancel()
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private fun squat(port: Int): ServerSocket = ServerSocket(port, 0, InetAddress.getByName("127.0.0.1")).also { opened += it }

    private fun get(url: String): Pair<Int, String> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            val code = connection.responseCode
            val body = (if (code < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            code to body
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `the redirect it hands out is the port it actually bound`() = runTest {
        val server = LoopbackRedirectServer(realScope, SignInFeedback())

        val uri = server.reserve()

        assertEquals(LoopbackRedirectServer.redirectUriFor(LoopbackRedirectServer.PORTS.first()), uri)
        server.release()
    }

    @Test
    fun `a taken port falls through to the next one`() = runTest {
        squat(LoopbackRedirectServer.PORTS[0])
        val server = LoopbackRedirectServer(realScope, SignInFeedback())

        val uri = server.reserve()

        // The one that matters: a second Muviss window, or any process holding
        // 53682, must not make signing in impossible.
        assertEquals(LoopbackRedirectServer.redirectUriFor(LoopbackRedirectServer.PORTS[1]), uri)
        server.release()
    }

    @Test
    fun `every port taken fails loudly rather than hanging`() = runTest {
        LoopbackRedirectServer.PORTS.forEach { squat(it) }
        val server = LoopbackRedirectServer(realScope, SignInFeedback())

        val error = runCatching { server.reserve() }.exceptionOrNull()

        // A thrown failure is what CoreSyncRepository turns into a Result the
        // profile screen can show. Returning a URL nothing is listening on
        // would reproduce exactly the silent hang this class exists to fix.
        assertNotNull(error)
        assertTrue(error.message.orEmpty().contains("in use"), "unhelpful message: ${error.message}")
    }

    @Test
    fun `the code in the redirect reaches the codes flow`() = runTest {
        val server = LoopbackRedirectServer(realScope, SignInFeedback())
        val uri = server.reserve()
        // UNDISPATCHED so the collector has actually subscribed before the
        // request is made. `codes` has no replay — a single-use authorization
        // code must not be re-delivered to a later collector — so an emission
        // with nobody listening is simply lost. Main.kt has no such race: it
        // subscribes at startup, long before any redirect.
        val received = async(start = CoroutineStart.UNDISPATCHED) { server.codes.first() }

        // On a real dispatcher: the request blocks until the handler responds,
        // and the handler's emission needs the test dispatcher free to run.
        val (status, body) = withContext(Dispatchers.IO) { get("$uri?code=abc123&state=xyz") }

        assertEquals(200, status)
        assertTrue(body.contains("You can close this tab"), "expected the close-the-tab page, got: $body")
        assertEquals("abc123", received.await())
    }

    @Test
    fun `a redirect carrying an error reports it instead of a code`() = runTest {
        val feedback = SignInFeedback()
        val server = LoopbackRedirectServer(realScope, feedback)
        val uri = server.reserve()

        withContext(Dispatchers.IO) { get("$uri?error=access_denied&error_description=User+declined") }

        // Declining on the provider's page is a real outcome, not a code that
        // failed to arrive — and it has to be distinguishable from never having
        // tried (see SignInFeedback's KDoc).
        assertEquals("User declined", feedback.lastFailure.first())
    }

    @Test
    fun `a request to any other path is not a sign-in`() = runTest {
        val server = LoopbackRedirectServer(realScope, SignInFeedback())
        val uri = server.reserve()
        val root = uri.removeSuffix(LoopbackRedirectServer.CALLBACK_PATH)

        val (status, _) = withContext(Dispatchers.IO) { get("$root/something-else?code=abc123") }

        assertEquals(404, status)
        server.release()
    }

    @Test
    fun `an abandoned sign-in times out, says so, and frees the port`() = runTest {
        val feedback = SignInFeedback()
        // The scope is this test's, so the timeout runs on virtual time.
        val server = LoopbackRedirectServer(this, feedback, timeout = 5.minutes)
        server.reserve()

        advanceTimeBy(5.minutes + 1.minutes)

        assertTrue(feedback.lastFailure.first().orEmpty().contains("timed out"))
        // Freed, not merely forgotten: the next attempt must be able to bind it
        // again, and the user's second try is the common case after a timeout.
        squat(LoopbackRedirectServer.PORTS.first()).close()
    }

    @Test
    fun `reserving twice supersedes the first attempt`() = runTest {
        val server = LoopbackRedirectServer(realScope, SignInFeedback())

        val first = server.reserve()
        val second = server.reserve()

        // Not the next port along: the abandoned attempt is released first, so
        // a user who clicks sign-in twice does not walk down the port list.
        assertEquals(first, second)
        server.release()
    }

    @Test
    fun `releasing twice is harmless`() = runTest {
        val server = LoopbackRedirectServer(realScope, SignInFeedback())
        server.reserve()

        server.release()
        server.release()

        assertNull(runCatching { server.release() }.exceptionOrNull())
    }
}
