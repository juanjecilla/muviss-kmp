package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.domain.PickHistory
import com.codingpit.muviss.feature.nextpick.domain.PickHistoryReader
import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteKind
import com.codingpit.muviss.feature.settings.api.TasteProfileApi
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.first

/**
 * The TMDB provider ids of the person's streaming services in the Taste Profile (#663). Services are keyed
 * by provider id, so a non-numeric value is not a service and is skipped rather than guessed at.
 */
internal suspend fun serviceIds(taste: TasteProfileApi): Set<Int> = taste.observe().first()
    .filter { it.kind == TasteKind.SERVICE }
    .mapNotNullTo(linkedSetOf()) { it.value.toIntOrNull() }

/**
 * The Taste Profile's liked genres as Muted Genre keys (#584). [TasteProfileApi.observe] already drops a
 * genre that is muted now (ObserveTasteProfileUseCase, ADR 0031), so a liked-then-muted genre cannot shape a
 * pick here. A value that is not `<movie|tv>:<id>` is ignored.
 */
internal suspend fun likedGenreKeys(taste: TasteProfileApi): Set<GenreKey> = taste.observe().first()
    .filter { it.kind == TasteKind.GENRE }
    .mapNotNullTo(linkedSetOf(), ::genreKeyOf)

/** Mirrors `TasteRules.genreKeyOf` in `:feature:settings:domain`, which this module may not depend on. */
private fun genreKeyOf(item: TasteItem): GenreKey? {
    val type = MediaType.entries.firstOrNull { it.wireName == item.value.substringBefore(':', "") } ?: return null
    val id = item.value.substringAfter(':', "")
    return if (id.isEmpty()) null else GenreKey(type, id)
}

/** Adds the Taste Profile's liked genres to the history another reader built (#584). */
internal class TasteHistoryReader(
    private val inner: PickHistoryReader,
    private val taste: () -> TasteProfileApi,
) : PickHistoryReader {
    override suspend fun read(criteria: PickCriteria): PickHistory = inner.read(criteria).copy(likedGenres = likedGenreKeys(taste()))
}
