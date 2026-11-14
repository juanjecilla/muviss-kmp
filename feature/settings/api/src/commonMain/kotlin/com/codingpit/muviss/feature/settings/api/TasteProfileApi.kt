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
 * The fixed options a person chooses from, shared by Settings' editor and the first-run introduction
 * (#583). Static on purpose: they are offered offline, on the first launch, before anything has loaded.
 * Genres are not here: they come from TMDB's catalogue, which needs the network.
 *
 * Services are keyed by TMDB provider id, the id `watchProviders(id, region)` reports, so a later
 * consumer matches by id and never by name. The list is curated and must be checked against
 * TMDB's own catalogue (a follow-up issue); an id that is wrong here shows a logo for the wrong
 * service rather than failing.
 */
object TasteChoices {
    /** Movies, series, or limited series. Labels are localized in the UI from these keys. */
    val formats: List<TasteItem> = listOf("MOVIE", "SERIES", "LIMITED_SERIES").map { TasteItem(TasteKind.FORMAT, it, it) }

    val services: List<TasteItem> = listOf(
        "8" to "Netflix",
        "9" to "Amazon Prime Video",
        "337" to "Disney+",
        "1899" to "Max",
        "350" to "Apple TV+",
        "15" to "Hulu",
        "531" to "Paramount+",
    ).map { (id, name) -> TasteItem(TasteKind.SERVICE, id, name) }

    /** Names are in each language's own script, so they need no translation. */
    val languages: List<TasteItem> = listOf(
        "en" to "English",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "it" to "Italiano",
        "pt" to "Português",
        "ja" to "日本語",
        "ko" to "한국어",
        "zh" to "中文",
        "hi" to "हिन्दी",
    ).map { (code, name) -> TasteItem(TasteKind.LANGUAGE, code, name) }
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
