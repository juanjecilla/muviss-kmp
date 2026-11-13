package com.codingpit.muviss.feature.search.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.crash.CrashReporter
import com.codingpit.muviss.core.database.DiscoverCacheQueries
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.feature.search.domain.DiscoverCache
import com.codingpit.muviss.feature.search.domain.Saved
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [DiscoverCache] over the `discoverCache` table (#724): each TMDB answer stored as JSON under a key that names
 * its section, page and TMDB language. A row that no longer decodes, because an app update changed the model, is
 * a miss, so the caller fetches it again rather than crashing.
 */
class SqlDelightDiscoverCache(
    private val queries: DiscoverCacheQueries,
    private val locale: MetadataLocale,
    private val dispatchers: AppDispatchers,
) : DiscoverCache {

    // Unknown keys are tolerated: a newer build may have stored fields this one does not model.
    private val json = Json { ignoreUnknownKeys = true }
    private val genresSerializer = ListSerializer(Genre.serializer())
    private val pageSerializer = PagedResult.serializer(MediaSummary.serializer())

    override suspend fun genres(type: MediaType): Saved<List<Genre>>? = read(genresKey(type)) { raw, at ->
        Saved(json.decodeFromString(genresSerializer, raw), at)
    }

    override suspend fun saveGenres(type: MediaType, genres: List<Genre>, fetchedAtEpochMs: Long) {
        write(genresKey(type), json.encodeToString(genresSerializer, genres), fetchedAtEpochMs)
    }

    override suspend fun popular(type: MediaType, page: Int): Saved<PagedResult<MediaSummary>>? = read(popularKey(type, page)) { raw, at ->
        Saved(json.decodeFromString(pageSerializer, raw), at)
    }

    override suspend fun savePopular(type: MediaType, page: Int, result: PagedResult<MediaSummary>, fetchedAtEpochMs: Long) {
        write(popularKey(type, page), json.encodeToString(pageSerializer, result), fetchedAtEpochMs)
    }

    private suspend fun <T> read(section: String, decode: (String, Long) -> Saved<T>): Saved<T>? = withContext(dispatchers.io) {
        val row = queries.selectByKey(cacheKey(section)).awaitAsOneOrNull() ?: return@withContext null
        try {
            decode(row.json, row.fetchedAtEpochMs)
        } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
            // SerializationException extends IllegalArgumentException. The stored text is not shown anywhere, so
            // the error itself is not kept; a miss is the whole answer.
            null
        }
    }

    /**
     * Best-effort: a storage failure (a full disk, say) costs this copy, never the TMDB answer it was stored from,
     * so the refresh that produced it still completes. Cancellation is not a failure and is rethrown.
     */
    private suspend fun write(section: String, body: String, fetchedAtEpochMs: Long) {
        runCatching {
            withContext(dispatchers.io) {
                queries.upsert(cacheKey = cacheKey(section), json = body, fetchedAtEpochMs = fetchedAtEpochMs)
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            CrashReporter.recordException(error)
        }
    }

    private fun genresKey(type: MediaType) = "genres:${type.wireName}"

    private fun popularKey(type: MediaType, page: Int) = "popular:${type.wireName}:$page"

    /** The language is read per call, so a language change finds nothing under the old key. */
    private fun cacheKey(section: String) = "$section|${locale.language}"
}
