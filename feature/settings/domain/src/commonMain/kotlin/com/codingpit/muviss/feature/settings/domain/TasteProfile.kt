package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.feature.nextpick.api.PickCompany
import com.codingpit.muviss.feature.settings.api.TasteChoices
import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteKind
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
 * The fixed options the editor offers. Formats, services and languages are [TasteChoices] in
 * `:feature:settings:api`, shared with the first-run introduction; only companies, which need the
 * Pick Session's enum, live here.
 */
object TasteCatalog {
    /** Stored upper-case, matching the PickCompany names the Pick Session already uses. */
    val companies: List<TasteItem> = PickCompany.entries.map { TasteItem(TasteKind.COMPANY, it.name, it.name) }

    val formats: List<TasteItem> get() = TasteChoices.formats
    val services: List<TasteItem> get() = TasteChoices.services
    val languages: List<TasteItem> get() = TasteChoices.languages
}

/**
 * Every liked item, live, less any genre that is muted now. A Muted Genre wins at read time: a
 * like can arrive without passing [SetTasteItemUseCase] (pulled from a device where the genre is
 * not muted, restored from a backup, or liked before the mute), and the row is kept rather than
 * deleted, so it is the other device's preference left alone, and unmuting brings the like back.
 */
class ObserveTasteProfileUseCase(
    private val repository: TasteProfileRepository,
    private val mutedGenres: Flow<Set<GenreKey>>,
) {
    operator fun invoke(): Flow<List<TasteItem>> = combine(repository.observe(), mutedGenres) { items, muted ->
        items.filter { item -> TasteRules.canLike(item, muted) }
    }
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
