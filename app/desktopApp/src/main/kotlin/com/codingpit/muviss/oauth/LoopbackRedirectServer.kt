package com.codingpit.muviss.oauth

import com.codingpit.muviss.core.sync.OAUTH_CODE_PARAM
import com.codingpit.muviss.core.sync.OAuthRedirectTarget
import com.codingpit.muviss.core.sync.SignInFeedback
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Desktop's half of the OAuth round trip (ADR 0017): a throwaway HTTP server on
 * loopback that catches GoTrue's redirect and hands the authorization code back
 * to the app.
 *
 * Desktop cannot use the `muviss://` scheme the other platforms use. jpackage
 * writes neither a Windows registry protocol handler nor a Linux `.desktop`
 * `MimeType` entry, and a scheme is owned by an *installed* bundle, so it would
 * be dead under `./gradlew run` — the way this app is actually developed. A
 * loopback redirect is what RFC 8252 prescribes for native apps and is the one
 * shape that behaves identically on all three desktop OSes.
 *
 * The listener exists only for the duration of an attempt. It is opened by
 * [reserve] (which is why reserving can fail) and closed the moment a code
 * arrives, the user abandons the flow, or [TIMEOUT] elapses. Nothing is
 * listening while the user is not signing in.
 *
 * PKCE still carries the security here, exactly as it does for the custom
 * scheme: any local process can bind a loopback port, so possession of the
 * code alone must not be enough to obtain a session.
 */
class LoopbackRedirectServer(
    private val scope: CoroutineScope,
    private val signInFeedback: SignInFeedback,
    private val timeout: Duration = TIMEOUT,
    private val ports: List<Int> = PORTS,
) : OAuthRedirectTarget {

    private val _codes = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * Authorization codes as they arrive. A flow rather than a return value
     * from [reserve] because the code is redeemed at app scope, through the
     * same `MuvissApp(oauthCode = ...)` parameter Android's `MainActivity`
     * feeds — keeping `CompleteOAuthOnRedirect` the only place in the codebase
     * that calls `completeOAuth`.
     */
    val codes: SharedFlow<String> = _codes.asSharedFlow()

    private var server: HttpServer? = null
    private var timeoutJob: Job? = null

    // A plain monitor rather than @Synchronized (not allowed on a suspend
    // fun) or a Mutex (release() is called from the server's own request
    // thread and cannot suspend). Neither body has a suspension point, so a
    // blocking lock held this briefly is the honest primitive.
    private val lock = Any()

    override suspend fun reserve(): String = synchronized(lock) {
        // A second sign-in click supersedes the first: the abandoned attempt's
        // code would fail PKCE anyway, since beginOAuth has already replaced
        // the stored verifier.
        releaseLocked()

        val bound = ports.firstNotNullOfOrNull { port ->
            try {
                // Explicitly loopback, never 0.0.0.0 — this must not be
                // reachable from the network.
                HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0) to port
            } catch (_: IOException) {
                null // in use, by another Muviss window or anything else
            }
        } ?: error(
            "Couldn't start the sign-in listener: ports ${ports.joinToString()} are all in use. " +
                "Close the other Muviss window, or whatever is using them, and try again.",
        )

        val (httpServer, port) = bound
        httpServer.createContext(CALLBACK_PATH) { exchange -> exchange.handleCallback() }
        // null = a default single-threaded executor; one request is all this
        // ever serves.
        httpServer.executor = null
        httpServer.start()
        server = httpServer

        timeoutJob = scope.launch {
            delay(timeout)
            // Same reasoning as SignInFeedback's own KDoc: a sign-in that
            // failed must not look identical to one that was never attempted.
            signInFeedback.report("Sign-in timed out after ${timeout.inWholeMinutes} minutes. Try again.")
            release()
        }

        redirectUriFor(port)
    }

    override fun release() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        timeoutJob?.cancel()
        timeoutJob = null
        // 0 = do not wait for in-flight exchanges. The only exchange this
        // server ever handles has already written its response by the time
        // release() runs from the handler.
        server?.stop(0)
        server = null
    }

    private fun HttpExchange.handleCallback() {
        val code = requestURI.rawQuery.queryParam(OAUTH_CODE_PARAM)
        val error = requestURI.rawQuery.queryParam("error_description")
            ?: requestURI.rawQuery.queryParam("error")

        when {
            code != null -> {
                respond(PAGE_SUCCESS)
                _codes.tryEmit(code)
            }

            // GoTrue redirects here with an `error` rather than a code when the
            // user declines on the provider's page, or the flow state expired.
            else -> {
                respond(PAGE_FAILURE)
                signInFeedback.report(error ?: "The sign-in redirect carried no authorization code.")
            }
        }
        release()
    }

    private fun HttpExchange.respond(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    companion object {
        /**
         * Every port here must also be in `supabase/config.toml`'s
         * `additional_redirect_urls` and pushed with `supabase config push` —
         * GoTrue refuses to redirect anywhere that is not on the allow-list, so
         * an unlisted port fails in the browser rather than at compile time.
         * Three, so a second Muviss window (or an unrelated squatter) does not
         * make signing in impossible.
         */
        val PORTS: List<Int> = listOf(53682, 53683, 53684)

        const val CALLBACK_PATH: String = "/auth-callback"

        /** Generous: the user may have to create an account on the provider first. */
        val TIMEOUT: Duration = 5.minutes

        fun redirectUriFor(port: Int): String = "http://127.0.0.1:$port$CALLBACK_PATH"

        private const val PAGE_SUCCESS =
            "<!doctype html><meta charset=utf-8><title>Muviss</title>" +
                "<body style=\"font-family:system-ui;text-align:center;padding-top:4rem\">" +
                "<h1>You're signed in</h1><p>You can close this tab and go back to Muviss.</p>"

        private const val PAGE_FAILURE =
            "<!doctype html><meta charset=utf-8><title>Muviss</title>" +
                "<body style=\"font-family:system-ui;text-align:center;padding-top:4rem\">" +
                "<h1>Sign-in didn't finish</h1><p>You can close this tab. Muviss will explain what went wrong.</p>"
    }
}

/**
 * Reads one parameter out of a raw query string. Hand-rolled because the JDK
 * has no query parser and pulling in a URL library for one line of `split`
 * would be the larger cost — and `URI.getQuery()` cannot be used instead: it
 * decodes the whole string first, so a `+` or `%26` inside a code would be
 * indistinguishable from a separator.
 */
internal fun String?.queryParam(name: String): String? = this
    ?.split('&')
    ?.firstOrNull { it.substringBefore('=') == name }
    ?.substringAfter('=', "")
    ?.takeIf { it.isNotEmpty() }
    ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
