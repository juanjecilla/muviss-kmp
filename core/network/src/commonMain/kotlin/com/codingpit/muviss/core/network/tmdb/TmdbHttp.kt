package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.MetadataError
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * How the TMDB client retries. Three attempts in total (two retries) with
 * exponential backoff; a `Retry-After` header is honoured when it is longer
 * than the backoff, and a `Retry-After` longer than [maxHonouredRetryAfterSeconds]
 * is not waited out at all — a spinner that long is worse than an error the
 * user can act on.
 *
 * [delay] exists so tests can record the waits instead of sleeping through
 * them; production leaves it null (the plugin's own `delay`).
 */
internal class TmdbRetryPolicy(
    val maxAttempts: Int = 3,
    val jitterMs: Long = 1_000L,
    val maxHonouredRetryAfterSeconds: Int = 10,
    val delay: (suspend (Long) -> Unit)? = null,
)

/**
 * Derives the TMDB flavour of [this] shared client. [HttpClient.config] reuses
 * the engine (one connection pool for the whole app) and copies the base
 * plugins, then adds what only TMDB wants:
 *
 * - `expectSuccess` plus a validator that turns a non-2xx status into a
 *   [MetadataError]. Without it an error body decodes into
 *   `TmdbPageDto`'s all-defaults shape and a rate limit reads as "No titles
 *   found".
 * - [HttpRequestRetry] for 429 and 5xx and for transport failures.
 * - [HttpCache] when [cached], in memory and per client: it honours TMDB's own
 *   `Cache-Control`/`ETag`, dies with the process, and is a client-side plugin,
 *   so it behaves the same on OkHttp, Darwin and Js.
 *
 * The Supabase client is *not* derived through this, and must not be.
 */
internal fun HttpClient.withTmdbPolicy(policy: TmdbRetryPolicy, cached: Boolean = true): HttpClient = config {
    expectSuccess = true
    install(HttpRequestRetry) {
        retryIf(maxRetries = policy.maxAttempts - 1) { _, response -> response.isRetryable(policy) }
        retryOnExceptionIf(maxRetries = policy.maxAttempts - 1) { _, cause -> cause.isTransportFailure() }
        exponentialDelay(randomizationMs = policy.jitterMs)
        policy.delay?.let { recorder -> delay(recorder) }
    }
    if (cached) install(HttpCache)
    HttpResponseValidator {
        validateResponse { response ->
            response.toMetadataErrorOrNull()?.let { throw it }
        }
    }
}

/**
 * Runs one TMDB call so that only a [MetadataError] (or a cancellation) can
 * come out of it. This is the choke point that makes the "no exception text
 * reaches the UI" rule structural: whatever Ktor, kotlinx.serialization or the
 * platform throws, including messages that embed the request URL, is replaced
 * by a fixed-text error with no cause.
 */
@Suppress("TooGenericExceptionCaught") // Catching everything is the point: nothing but a MetadataError may escape.
internal suspend inline fun <T> tmdbCall(block: () -> T): T = try {
    block()
} catch (error: MetadataError) {
    throw error
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    throw failure.toMetadataError()
}

/** Status to error: 429, 401 and 404 have their own cases, anything else non-2xx is [MetadataError.Unknown]. */
internal fun HttpResponse.toMetadataErrorOrNull(): MetadataError? = when {
    status.value in SUCCESS_RANGE -> null
    status == HttpStatusCode.TooManyRequests -> MetadataError.RateLimited(retryAfterSeconds())
    status == HttpStatusCode.Unauthorized -> MetadataError.Unauthorized()
    status == HttpStatusCode.NotFound -> MetadataError.NotFound()
    else -> MetadataError.Unknown()
}

/**
 * Exception to error. Decode failures are [MetadataError.Unknown]; a
 * [ResponseException] falls back to its status; and everything else is a
 * transport failure (I/O, timeout, DNS, `fetch` rejecting, a Darwin `NSError`
 * wrapper) and therefore [MetadataError.Offline] — asking each platform's
 * engine which of its exception types mean "no connection" is a list that is
 * wrong on the day it ships, whereas "the request never produced a response"
 * is what those all have in common.
 */
internal fun Throwable.toMetadataError(): MetadataError = when (this) {
    is MetadataError -> this
    is ResponseException -> response.toMetadataErrorOrNull() ?: MetadataError.Unknown()
    is SerializationException, is ContentConvertException, is NoTransformationFoundException -> MetadataError.Unknown()
    else -> MetadataError.Offline()
}

/** Attaches the credential: the v4 token as a bearer header when configured, else the v3 key as `api_key`. */
internal fun HttpRequestBuilder.authorize(credentials: TmdbCredentials) {
    when {
        credentials.readToken.isNotBlank() -> header(HttpHeaders.Authorization, "Bearer ${credentials.readToken}")
        credentials.apiKey.isNotBlank() -> parameter("api_key", credentials.apiKey)
    }
}

/**
 * The two ways TMDB accepts a credential. [readToken] (v4) is preferred
 * because it travels in a header and so never appears in a URL, a log line or
 * an exception message; [apiKey] (v3) is the fallback and lives in the query
 * string, which is why every error path here drops the URL.
 */
internal class TmdbCredentials(val readToken: String = "", val apiKey: String = "") {
    override fun toString(): String = "TmdbCredentials(<redacted>)"
}

private fun HttpResponse.retryAfterSeconds(): Int? = headers[HttpHeaders.RetryAfter]?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

private fun HttpResponse.isRetryable(policy: TmdbRetryPolicy): Boolean = when {
    status == HttpStatusCode.TooManyRequests -> (retryAfterSeconds() ?: 0) <= policy.maxHonouredRetryAfterSeconds
    else -> status.value in SERVER_ERROR_RANGE
}

/** Only failures that produced no response are worth repeating, and a whole-request timeout has already spent its budget. */
private fun Throwable.isTransportFailure(): Boolean = when (this) {
    is MetadataError, is HttpRequestTimeoutException -> false
    is CancellationException -> false
    is ResponseException, is SerializationException, is ContentConvertException -> false
    else -> true
}

private val SUCCESS_RANGE = 200..299
private val SERVER_ERROR_RANGE = 500..599
