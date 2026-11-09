package com.codingpit.muviss.feature.settings.api

import kotlinx.coroutines.flow.Flow

/**
 * What a [TasteItem] is about (EPIC 51, #402, CONTEXT.md "Taste Profile"). The name is
 * what is stored and synced, so renaming a constant is a data migration, not a refactor.
 */
enum class TasteKind { GENRE, FORMAT, SERVICE, LANGUAGE, COMPANY }

/**
 * One thing the person said they like.
 *
 * [value] is the stable key within its [kind]: `movie:28` or `tv:18` for a genre
 * (the same `<type>:<tmdb id>` a Muted Genre is keyed by), a TMDB provider id for a
 * service, an ISO 639-1 code for a language, and the upper-case name for a company or
 * format. [label] is what to show, stored with the item so it reads offline.
 */
data class TasteItem(val kind: TasteKind, val value: String, val label: String) {
    /** The row key shared with the database and with sync: `KIND:value`. */
    val id: String get() = "${kind.name}:$value"
}

/**
 * The Taste Profile as peers see it (EPIC 51). Read by the Pick Session and, later,
 * For you and Triage (#406); written from the Settings editor and the onboarding page.
 * Nothing here is inferred from what the person watches: it is only what they said.
 */
interface TasteProfileApi {
    /** The liked items, ordered by kind then label. Emits again on every change. */
    fun observe(): Flow<List<TasteItem>>

    /**
     * Likes or unlikes [item]. Returns false, and changes nothing, when liking a genre
     * the person has muted: a genre is liked or muted, never both (ADR 0031). Unliking
     * always succeeds.
     */
    suspend fun setLiked(item: TasteItem, liked: Boolean): Boolean
}
