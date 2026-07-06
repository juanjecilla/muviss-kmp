package com.codingpit.muviss.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
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

/** Builds a configured Ktor client shared across all providers. */
fun createHttpClient(enableLogging: Boolean = false): HttpClient = createPlatformHttpClient {
    install(ContentNegotiation) {
        json(muvissJson)
    }
    if (enableLogging) {
        install(Logging) { level = LogLevel.INFO }
    }
}
