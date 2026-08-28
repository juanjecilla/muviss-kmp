package com.codingpit.muviss.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
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
 * Builds a configured Ktor client shared across all providers.
 *
 * [HttpTimeout] is not optional: several screens fan a single user action out
 * into dozens of sequential requests (the library refresh re-fetches every
 * saved title, and a TV title costs one request per season), so a socket that
 * stalls rather than fails takes the whole operation — and its spinner — with
 * it. The caps below bound that.
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
        install(Logging) { level = LogLevel.INFO }
    }
}

private const val REQUEST_TIMEOUT_MS = 30_000L
private const val CONNECT_TIMEOUT_MS = 15_000L
private const val SOCKET_TIMEOUT_MS = 20_000L
