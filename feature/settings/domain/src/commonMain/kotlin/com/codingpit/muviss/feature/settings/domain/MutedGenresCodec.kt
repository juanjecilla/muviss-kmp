package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MutedGenres

/** One muted genre as Settings lists it: the [key] filtering uses, and the [name] it showed when it was muted. */
data class MutedGenreEntry(val key: GenreKey, val name: String)

/**
 * `appSettings.mutedGenres` on disk: `;`-joined `<movie|tv>:<tmdb id>:<name>`. TMDB genre names contain
 * neither `;` nor `:` ("Action & Adventure", "War & Politics"), and [decode] splits on the first two `:`
 * only, so a name that did would still round-trip. An entry that does not parse is dropped rather than
 * failing the whole list, since the value is read on every screen that suggests titles.
 */
object MutedGenresCodec {
    private const val ENTRY_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = ":"

    /** Must equal the `mutedGenres` column DEFAULT in `AppSettings.sq` and `18.sqm`; a test pins it. */
    val DEFAULTS: List<MutedGenreEntry> = listOf(
        MutedGenreEntry(GenreKey(MediaType.TV, "10767"), "Talk"),
        MutedGenreEntry(GenreKey(MediaType.TV, "10763"), "News"),
        MutedGenreEntry(GenreKey(MediaType.TV, "10764"), "Reality"),
    )

    fun encode(entries: List<MutedGenreEntry>): String = entries.joinToString(ENTRY_SEPARATOR) {
        "${it.key.type.wireName}$FIELD_SEPARATOR${it.key.id}$FIELD_SEPARATOR${it.name.replace(ENTRY_SEPARATOR, " ")}"
    }

    fun decode(stored: String): List<MutedGenreEntry> = stored.split(ENTRY_SEPARATOR).mapNotNull { entry ->
        val parts = entry.split(FIELD_SEPARATOR, limit = 3)
        val type = MediaType.entries.firstOrNull { it.wireName == parts.getOrNull(0) }
        val id = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (type == null || id == null) null else MutedGenreEntry(GenreKey(type, id), parts.getOrElse(2) { "" })
    }.distinctBy { it.key }

    fun toMutedGenres(entries: List<MutedGenreEntry>): MutedGenres = MutedGenres(entries.mapTo(linkedSetOf()) { it.key })
}
