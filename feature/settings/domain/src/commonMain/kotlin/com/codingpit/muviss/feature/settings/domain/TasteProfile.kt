package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.feature.nextpick.api.PickCompany
import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteKind
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Persistence for the Taste Profile. Implemented by `:feature:settings:data` over `tasteProfile`. */
interface TasteProfileRepository {
    fun observe(): Flow<List<TasteItem>>

    /** Writes [item] liked or unliked. Liking an item already liked changes nothing, so the stamp does not move. */
    suspend fun setLiked(item: TasteItem, liked: Boolean)
}

/**
 * The rule that a genre is liked or muted, never both (ADR 0031, CONTEXT.md). Pure, so the
 * editor and the API share one answer. A genre item is keyed `<movie|tv>:<tmdb id>`, the
 * same key a Muted Genre uses; every other kind is never muted.
 */
object TasteRules {
    fun canLike(item: TasteItem, muted: Set<GenreKey>): Boolean {
        if (item.kind != TasteKind.GENRE) return true
        val key = genreKeyOf(item) ?: return true
        return key !in muted
    }

    /** The Muted Genre key a genre item stands for, or null when its value is not one. */
    fun genreKeyOf(item: TasteItem): GenreKey? {
        if (item.kind != TasteKind.GENRE) return null
        val type = item.value.substringBefore(':', missingDelimiterValue = "")
        val id = item.value.substringAfter(':', missingDelimiterValue = "")
        val mediaType = MediaType.entries.firstOrNull { it.wireName == type } ?: return null
        return if (id.isEmpty()) null else GenreKey(mediaType, id)
    }

    /** The genre item for a TMDB genre: the value a Muted Genre would use for the same genre. */
    fun genreItem(type: MediaType, id: String, name: String): TasteItem = TasteItem(TasteKind.GENRE, "${type.wireName}:$id", name)
}

/**
 * The fixed options a person can choose from, except genres (which come from TMDB's catalogue
 * and so need the network). Static on purpose: the onboarding page offers these offline, on the
 * first launch, before anything has loaded.
 *
 * Services are keyed by TMDB provider id, the id `watchProviders(id, region)` reports, so a later
 * consumer matches by id and never by name. The list is curated and must be checked against
 * TMDB's own catalogue (a follow-up issue); an id that is wrong here shows a logo for the wrong
 * service rather than failing.
 */
object TasteCatalog {
    /** Stored upper-case, matching the PickCompany names the Pick Session already uses. */
    val companies: List<TasteItem> = PickCompany.entries.map { TasteItem(TasteKind.COMPANY, it.name, it.name) }

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

/** Every liked item, live. */
class ObserveTasteProfileUseCase(private val repository: TasteProfileRepository) {
    operator fun invoke(): Flow<List<TasteItem>> = repository.observe()
}

/**
 * Likes or unlikes an item, refusing to like a Muted Genre. The muted set is read at the moment
 * of the write, not cached: a genre muted a second ago must not be liked in the same tap.
 */
class SetTasteItemUseCase(
    private val repository: TasteProfileRepository,
    private val mutedGenres: Flow<Set<GenreKey>>,
) {
    suspend operator fun invoke(item: TasteItem, liked: Boolean): Boolean {
        if (!liked) {
            repository.setLiked(item, liked = false)
            return true
        }
        if (!TasteRules.canLike(item, mutedGenres.first())) return false
        repository.setLiked(item, liked = true)
        return true
    }
}
