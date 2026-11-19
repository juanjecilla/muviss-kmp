package com.codingpit.muviss.feature.search.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.crash.CrashReporter
import com.codingpit.muviss.core.database.DiscoverCacheQueries
import com.codingpit.muviss.core.database.ForYouCacheQueries
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.feature.search.domain.DiscoverCache
import com.codingpit.muviss.feature.search.domain.ForYouAnswer
import com.codingpit.muviss.feature.search.domain.Saved
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A "For you" copy as stored: the seeds it was written for, and each seed's answer (#785). */
@Serializable
private data class ForYouCopy(val seeds: List<String>, val answers: List<ForYouAnswerCopy>)

@Serializable
private data class ForYouAnswerCopy(val seed: String, val items: List<MediaSummary>, val fetchedAtEpochMs: Long)

/**
 * [DiscoverCache] over the `discoverCache` table (#724): each TMDB answer stored as JSON under a key that names
 * its section, page and TMDB language. A row that no longer decodes, because an app update changed the model, is
 * a miss, so the caller fetches it again rather than crashing. "For you" (#785) is one row in `forYouCache`, keyed
 * by a hash of its seed set and the language.
 */
class SqlDelightDiscoverCache(
    private val queries: DiscoverCacheQueries,
    private val forYouQueries: ForYouCacheQueries,
    private val locale: MetadataLocale,
    private val dispatchers: AppDispatchers,
) : DiscoverCache {

    // Unknown keys are tolerated: a newer build may have stored fields this one does not model.
    private val json = Json { ignoreUnknownKeys = true }
    private val genresSerializer = ListSerializer(Genre.serializer())
    private val pageSerializer = PagedResult.serializer(MediaSummary.serializer())
    private val forYouSerializer = ForYouCopy.serializer()
    private val writes = Mutex()

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

    override suspend fun forYou(seeds: List<MediaId>): List<ForYouAnswer>? = withContext(dispatchers.io) {
        val row = forYouQueries.selectByKey(forYouKey(seeds)).awaitAsOneOrNull() ?: return@withContext null
        try {
            val copy = json.decodeFromString(forYouSerializer, row.json)
            // The key is a 64-bit hash: the seeds stored with the copy must be the ones asked for, or it is a miss.
            if (copy.seeds != seedIdentities(seeds)) return@withContext null
            copy.answers.map { ForYouAnswer(MediaId.parse(it.seed), it.items, it.fetchedAtEpochMs) }
        } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
            // Same as the shelves: the stored text is not shown anywhere, and a miss is the whole answer.
            null
        }
    }

    /**
     * Best-effort, like [write]. Each seed keeps whichever of its stored and new answers was fetched later, so a
     * superseded refresh landing last cannot replace a newer answer. Only this seed set's row is kept.
     */
    override suspend fun saveForYou(seeds: List<MediaId>, answers: List<ForYouAnswer>) {
        runCatching {
            withContext(dispatchers.io) {
                writes.withLock {
                    val stored = forYou(seeds).orEmpty()
                    val newest = (answers + stored).groupBy { it.seed }.mapValues { (_, same) -> same.maxBy { it.fetchedAtEpochMs } }
                    val copy = ForYouCopy(
                        seeds = seedIdentities(seeds),
                        answers = seeds.mapNotNull { newest[it] }.map { ForYouAnswerCopy(it.seed.toString(), it.items, it.fetchedAtEpochMs) },
                    )
                    val key = forYouKey(seeds)
                    forYouQueries.upsert(cacheKey = key, json = json.encodeToString(forYouSerializer, copy))
                    forYouQueries.deleteOthers(keep = key)
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            CrashReporter.recordException(error)
        }
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
                // Read, compare and write one at a time: a superseded refresh can still land its last write, and it
                // must not replace a newer copy stored meanwhile.
                writes.withLock {
                    val key = cacheKey(section)
                    val current = queries.selectByKey(key).awaitAsOneOrNull()
                    if (current == null || fetchedAtEpochMs >= current.fetchedAtEpochMs) {
                        queries.upsert(cacheKey = key, json = body, fetchedAtEpochMs = fetchedAtEpochMs)
                    }
                }
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

    /** Sorted, so the same set in any order is the same copy: the seeds are a set, not a sequence. */
    private fun seedIdentities(seeds: List<MediaId>): List<String> = seeds.map { it.toString() }.sorted()

    private fun forYouKey(seeds: List<MediaId>): String {
        val identity = seedIdentities(seeds).joinToString(",") + "|" + locale.language
        return "foryou:" + fnv1a64(identity).toULong().toString(16).padStart(16, '0')
    }

    private companion object {
        /** FNV-1a, 64 bit. A fixed function, unlike `String.hashCode`, so a key reads the same on every platform. */
        fun fnv1a64(text: String): Long {
            var hash = -3750763034362895579L // 0xcbf29ce484222325, the FNV offset basis
            for (byte in text.encodeToByteArray()) {
                hash = (hash xor (byte.toLong() and 0xff)) * 0x100000001B3L
            }
            return hash
        }
    }
}
