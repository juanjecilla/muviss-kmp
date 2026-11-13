package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.feature.nextpick.api.PickCompany
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.domain.PickHistory
import com.codingpit.muviss.feature.nextpick.domain.PickHistoryReader
import com.codingpit.muviss.feature.nextpick.domain.PickQuestion
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

/**
 * What the Taste Profile answers before a Pick Session opens (#741): the questions it settles, and the criteria
 * it contributes. Both go into `PickContext`, which the session reads.
 */
internal data class ProfileAnswers(val answered: Set<PickQuestion>, val criteria: PickCriteria)

/**
 * Turns the liked formats, languages and companies into [ProfileAnswers] (#741). One liked value of a kind is
 * an answer; two or more are not, because no single one of them says what the person wants tonight, so the
 * question stays open. A value the rule does not know is skipped, as an unknown genre or service is.
 *
 * - **Format** (`MOVIE`, `SERIES`, `LIMITED_SERIES`) answers [PickQuestion.FORMAT_AND_TIME] with a movie or a show.
 *   The question is one screen, so its runtime half is settled with it, as the session already treats it.
 * - **Language** (an ISO 639-1 code) sets the original language. [PickQuestion.ERA_AND_SERVICES] stays asked, for
 *   its era and services, and keeps the language through its answer (`PickAnswer.EraAndServices`).
 * - **Company** (`ALONE`, `PARTNER`, `FAMILY`) answers [PickQuestion.COMPANY].
 */
internal fun profileAnswers(items: List<TasteItem>): ProfileAnswers {
    val answered = mutableSetOf<PickQuestion>()
    var criteria = PickCriteria()

    items.valuesOf(TasteKind.FORMAT).mapNotNull(::formatType).singleOrNull()?.let { type ->
        answered += PickQuestion.FORMAT_AND_TIME
        criteria = criteria.copy(type = type)
    }
    items.valuesOf(TasteKind.LANGUAGE).singleOrNull()?.let { code ->
        criteria = criteria.copy(originalLanguage = code)
    }
    items.valuesOf(TasteKind.COMPANY).mapNotNull(::companyOf).singleOrNull()?.let { company ->
        answered += PickQuestion.COMPANY
        criteria = criteria.copy(company = company)
    }
    return ProfileAnswers(answered, criteria)
}

private fun List<TasteItem>.valuesOf(kind: TasteKind): List<String> = filter { it.kind == kind }.map { it.value }.filter { it.isNotBlank() }

/** `SERIES` and `LIMITED_SERIES` are both shows: the commitment a limited series implies is its own question. */
private fun formatType(value: String): MediaType? = when (value) {
    "MOVIE" -> MediaType.MOVIE
    "SERIES", "LIMITED_SERIES" -> MediaType.TV
    else -> null
}

private fun companyOf(value: String): PickCompany? = PickCompany.entries.firstOrNull { it.name == value }

/** Adds the Taste Profile's liked genres to the history another reader built (#584). */
internal class TasteHistoryReader(
    private val inner: PickHistoryReader,
    private val taste: () -> TasteProfileApi,
) : PickHistoryReader {
    override suspend fun read(criteria: PickCriteria): PickHistory = inner.read(criteria).copy(likedGenres = likedGenreKeys(taste()))
}
