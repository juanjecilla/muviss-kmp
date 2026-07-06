package com.codingpit.muviss.models

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Identifies the origin of a piece of media. Open by design: a new
 * [MetadataProvider] registers a new source without a schema change.
 */
@Serializable
@JvmInline
value class SourceId(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        val TMDB = SourceId("tmdb")
    }
}

/** Movie or TV show. */
@Serializable
enum class MediaType {
    MOVIE,
    TV,
    ;

    val wireName: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): MediaType = entries.firstOrNull { it.wireName == value.lowercase() }
            ?: error("Unknown media type: $value")
    }
}

/**
 * Source-namespaced, stable identity for any media item, e.g. `tmdb:tv:1399`.
 * Namespacing prevents collisions across sources and lets the same title from
 * two sources be reconciled later (see [MediaAnchors.imdbId]).
 */
@Serializable
data class MediaId(
    val source: SourceId,
    val type: MediaType,
    val external: String,
) {
    override fun toString(): String = "$source:${type.wireName}:$external"

    companion object {
        fun parse(raw: String): MediaId {
            val parts = raw.split(":")
            require(parts.size == 3) { "Malformed MediaId: $raw" }
            return MediaId(SourceId(parts[0]), MediaType.fromWire(parts[1]), parts[2])
        }

        fun tmdbMovie(id: String) = MediaId(SourceId.TMDB, MediaType.MOVIE, id)

        fun tmdbTv(id: String) = MediaId(SourceId.TMDB, MediaType.TV, id)
    }
}

/**
 * Cross-source anchors used to reconcile the same title across providers.
 * IMDb id is the most widely-shared key.
 */
@Serializable
data class MediaAnchors(
    val imdbId: String? = null,
)
