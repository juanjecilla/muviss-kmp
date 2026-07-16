package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.MediaType

/**
 * Groups import rows that refer to the same title (e.g. one row per watched
 * episode of a show) into a single [ImportedTitle], merging their episode
 * lists and taking the first non-null rating seen. Shared by every parser so
 * this identity/merge logic isn't duplicated three times (Trakt's JSON is
 * naturally one event per row, both CSV formats are naturally one row per
 * episode for TV).
 */
internal class ImportTitleAccumulator {
    private val order = mutableListOf<String>()
    private val byKey = mutableMapOf<String, ImportedTitle>()

    fun add(key: String, title: ImportedTitle) {
        val existing = byKey[key]
        if (existing == null) {
            byKey[key] = title
            order += key
        } else {
            byKey[key] = existing.copy(
                rating = existing.rating ?: title.rating,
                watched = existing.watched || title.watched,
                episodes = (existing.episodes + title.episodes).distinct(),
            )
        }
    }

    fun build(): List<ImportedTitle> = order.map { byKey.getValue(it) }

    companion object {
        /**
         * Prefers an id-based identity (stable across title-text variations
         * between rows) and only falls back to title text when a row carries
         * no id at all — such a title still gets grouped consistently, but
         * (having no id) will end up unresolved at the id-mapping step.
         */
        fun key(ref: ExternalTitleRef, type: MediaType?, title: String): String = when {
            ref.imdbId != null -> "imdb:${ref.imdbId}"
            ref.tmdbId != null -> "tmdb:${ref.tmdbId}"
            else -> "title:$type:${title.trim().lowercase()}"
        }
    }
}
