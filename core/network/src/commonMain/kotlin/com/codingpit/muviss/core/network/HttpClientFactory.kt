package com.codingpit.muviss.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Provides the platform-specific Ktor engine (OkHttp / Darwin / Js). */
expect fun createPlatformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

internal val muvissJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

/**
 * Builds the base Ktor client shared across the app: one engine, one
 * connection pool, JSON, timeouts and (where the platform allows it) a
 * User-Agent.
 *
 * It is deliberately *policy-free*. `expectSuccess`, retry and the HTTP cache
 * are TMDB's concerns and are layered on by
 * [com.codingpit.muviss.core.network.tmdb.TmdbProvider] on a derived client
 * ([HttpClient.config] shares this client's engine); the Supabase sync client
 * reads non-2xx statuses itself and must not see them thrown. No credential is
 * set here either, for the same reason: a default header would ride along on
 * every request to every host.
 *
 * [HttpTimeout] is not optional: several screens fan a single user action out
 * into dozens of requests (the library refresh re-fetches every saved title),
 * so a socket that stalls rather than fails takes the whole operation — and
 * its spinner — with it.
 */
fun createHttpClient(enableLogging: Boolean = false): HttpClient = createPlatformHttpClient {
    install(ContentNegotiation) {
        json(muvissJson)
    }
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        socketTimeoutMillis = SOCKET_TIMEOUT_MS
    }
    if (enableLogging) {
        install(Logging) {
            level = LogLevel.INFO
            logger = RedactingLogger()
            sanitizeHeader { header -> header == HttpHeaders.Authorization }
        }
    }
}

/**
 * Identifies the app to the servers it calls. Installed by the OkHttp and
 * Darwin actuals only: on web the header is not in the CORS-safelist, so
 * adding it would turn every simple cross-origin GET into a preflighted one
 * (and browsers may silently drop it anyway).
 */
internal fun HttpClientConfig<*>.installUserAgent() {
    install(UserAgent) { agent = USER_AGENT }
}

internal const val USER_AGENT = "Muviss (Kotlin Multiplatform; +https://github.com/juanjecilla/muviss-kmp)"

private const val REQUEST_TIMEOUT_MS = 30_000L
private const val CONNECT_TIMEOUT_MS = 15_000L
private const val SOCKET_TIMEOUT_MS = 20_000L
