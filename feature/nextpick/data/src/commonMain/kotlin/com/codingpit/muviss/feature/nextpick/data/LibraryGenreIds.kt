package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Maps the genre *names* a library snapshot stores back to TMDB ids (#547, ADR 0031, ADR 0032), so Muted
 * Genres and mood matching reach library titles. No schema change: the names are already on the snapshot.
 *
 * Lookup order per [MediaType], first hit wins:
 * 1. the live catalogue in the current language, when the device is online. Memoised per language and
 *    type for the life of the process; a failed fetch is not memoised, so the next pick retries.
 * 2. [BundledGenreNames], en-US and es-ES, which need no network at all.
 *
 * Matching trims and ignores case. A name in neither list maps to nothing and is dropped: the title is
 * still offered, it just cannot be muted or matched by genre. A failed live fetch never fails the caller.
 */
internal class LibraryGenreIds(
    private val liveCatalogue: suspend (MediaType) -> List<Genre>,
    private val language: () -> String,
) {
    private val mutex = Mutex()
    private val fetched = mutableMapOf<Pair<String, MediaType>, List<Genre>>()

    /** The name index for [type]. Offline it reads only the bundled names, so it never waits on a timeout. */
    suspend fun index(type: MediaType, online: Boolean): GenreNameIndex {
        val live = if (online) liveFor(type) else emptyList()
        return GenreNameIndex(live + BundledGenreNames.genres(type))
    }

    private suspend fun liveFor(type: MediaType): List<Genre> {
        val key = language() to type
        mutex.withLock { fetched[key]?.let { return it } }
        val genres = runCatching { liveCatalogue(type) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            ?: return emptyList()
        mutex.withLock { fetched[key] = genres }
        return genres
    }
}

/** Genre names of one type, in lookup order. Built once per pick, then asked per candidate. */
internal class GenreNameIndex(genres: List<Genre>) {
    private val idByName: Map<String, String> = buildMap {
        genres.forEach { genre ->
            val name = normalizeGenreName(genre.name)
            if (name !in this) put(name, genre.id)
        }
    }

    /** The TMDB ids of [names], in order, dropping any name that maps to nothing. */
    fun idsOf(names: List<String>): List<String> = names.mapNotNull { idByName[normalizeGenreName(it)] }.distinct()
}

private fun normalizeGenreName(name: String): String = name.trim().lowercase()
