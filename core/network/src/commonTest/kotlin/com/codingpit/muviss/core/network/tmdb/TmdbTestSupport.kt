package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.network.DefaultMetadataLocale
import com.codingpit.muviss.core.network.muvissJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A provider over a [MockEngine] with the same base plugins `createHttpClient` installs, and waits recorded rather than slept. */
internal class ProviderFixture(
    credentials: TmdbCredentials = TmdbCredentials(apiKey = API_KEY),
    maxConcurrentRequests: Int = TmdbProvider.DEFAULT_MAX_CONCURRENT_REQUESTS,
    handler: MockRequestHandler,
) {
    // The engine handler can run on several threads at once (the concurrency tests).
    private val recording = Mutex()
    val requests = mutableListOf<HttpRequestData>()
    val delays = mutableListOf<Long>()

    val provider = TmdbProvider(
        client = HttpClient(
            MockEngine { request ->
                recording.withLock { requests += request }
                handler(request)
            },
        ) { install(ContentNegotiation) { json(muvissJson) } },
        credentials = credentials,
        locale = DefaultMetadataLocale(),
        retryPolicy = TmdbRetryPolicy(jitterMs = 0, delay = { delays += it }),
        maxConcurrentRequests = maxConcurrentRequests,
    )

    val urls: List<String> get() = requests.map { it.url.toString() }
}

internal const val API_KEY = "v3-secret-key-123"
internal const val READ_TOKEN = "v4-secret-token-456"

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

internal fun MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    extra: HeadersBuilder.() -> Unit = {},
) = respond(
    content = body,
    status = status,
    headers = Headers.build {
        appendAll(jsonHeaders)
        extra()
    },
)

internal const val EMPTY_PAGE = """{"page":1,"results":[],"total_pages":1}"""
internal const val ONE_RESULT_PAGE =
    """{"page":1,"total_pages":1,"results":[{"id":603,"media_type":"movie","title":"The Matrix","release_date":"1999-03-31"}]}"""

/** TMDB's documented `/tv/{id}` shape, with [seasonCount] real seasons plus the "Specials" season 0. */
internal fun tvShowJson(seasonCount: Int): String {
    val seasons = (0..seasonCount).joinToString(",") {
        """{"season_number":$it,"name":"Season $it","episode_count":2}"""
    }
    return """{"id":1399,"name":"Show","number_of_seasons":$seasonCount,"seasons":[$seasons],"status":"Ended","external_ids":{"imdb_id":"tt1"}}"""
}

/** One season object as TMDB documents it for `/tv/{id}/season/{n}` and for `append_to_response=season/{n}`. */
internal fun seasonJson(number: Int): String = """{"_id":"x$number","air_date":"2011-04-17","name":"Season $number","season_number":$number,"episodes":[
        {"season_number":$number,"episode_number":1,"name":"E1","air_date":"2011-04-17","runtime":55},
        {"season_number":$number,"episode_number":2,"name":"E2","air_date":"2011-04-24","runtime":55}]}"""

/** `/tv/{id}?append_to_response=season/a,season/b,...`: the show fields plus one `season/N` key per requested season. */
internal fun tvWithAppendedSeasons(requested: List<Int>, omit: Set<Int> = emptySet()): String {
    val appended = requested.filterNot { it in omit }.joinToString(",") { "\"season/$it\":${seasonJson(it)}" }
    return """{"id":1399,"name":"Show","seasons":[]${if (appended.isEmpty()) "" else ",$appended"}}"""
}
