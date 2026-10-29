package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.network.DiscoverFilters
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.nextpick.api.PickCompany
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickEra
import com.codingpit.muviss.feature.nextpick.api.PickRuntime
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.domain.CandidateSource
import com.codingpit.muviss.feature.nextpick.domain.FamilyCertifications
import com.codingpit.muviss.feature.nextpick.domain.MoodGenres
import com.codingpit.muviss.feature.nextpick.domain.PickCandidate
import com.codingpit.muviss.feature.nextpick.domain.PickCandidates
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.flow.first

/**
 * TMDB's `/discover` with the session's answers as filters (EPIC 51, ADR 0032). Silent offline and for
 * "my list": those have no catalogue to ask. Only page 1 is read, which is enough for an engine that
 * returns one pick or a handful.
 *
 * A result the library already holds is annotated with its status, so the engine can tell "something
 * new" from a title the person is watching or has finished.
 */
internal class DiscoverSource(
    private val metadata: MetadataProvider,
    private val collection: () -> CollectionApi,
    private val thisYear: () -> Int,
) : PickCandidates {

    override suspend fun gather(criteria: PickCriteria, context: PickContext): List<PickCandidate> {
        if (!context.online || criteria.source == PickSource.MY_LIST) return emptyList()
        val statuses = collection().observeSummaries().first().associate { it.mediaId to it.status }
        val types = listOfNotNull(criteria.type).ifEmpty { listOf(MediaType.MOVIE, MediaType.TV) }
        return types.flatMap { type ->
            browse(type, criteria).map { summary ->
                PickCandidate(
                    summary = summary,
                    source = CandidateSource.DISCOVER,
                    libraryStatus = statuses[summary.id],
                )
            }
        }
    }

    /** A failed TMDB call is no candidates from this source, so the backlog still answers (#548). */
    @Suppress("SwallowedException") // Offline or rate-limited: the session falls back to the other sources, nothing is shown.
    private suspend fun browse(type: MediaType, criteria: PickCriteria): List<MediaSummary> = try {
        metadata.discoverFiltered(type, page = 1, filters = filtersFor(criteria, type)).items
    } catch (failure: MetadataError) {
        emptyList()
    }

    private fun filtersFor(criteria: PickCriteria, type: MediaType): DiscoverFilters {
        val runtime = runtimeBounds(criteria.runtime)
        val era = eraBounds(criteria.era)
        val certification = criteria.company?.takeIf { it == PickCompany.FAMILY }?.let { FamilyCertifications.forRegion(null, type) }
        return DiscoverFilters(
            genreIds = MoodGenres.genreIds(criteria.moods, type),
            minRuntimeMinutes = runtime.first,
            maxRuntimeMinutes = runtime.second,
            originalLanguage = criteria.originalLanguage,
            releasedOnOrAfterEpochDay = era.first,
            releasedOnOrBeforeEpochDay = era.second,
            certificationCountry = certification?.country,
            certifications = certification?.allowed.orEmpty(),
        )
    }

    private fun runtimeBounds(runtime: PickRuntime?): Pair<Int?, Int?> = when (runtime) {
        null -> null to null
        PickRuntime.QUICK -> null to QUICK_MAX
        PickRuntime.EVENING -> null to EVENING_MAX
        PickRuntime.BINGE -> (EVENING_MAX + 1) to null
    }

    /** Epoch-day bounds for a release. "Classic" is an upper bound only; "new" a lower one. Coarse on purpose: a year is enough. */
    private fun eraBounds(era: PickEra?): Pair<Long?, Long?> {
        val year = thisYear()
        return when (era) {
            null -> null to null
            PickEra.NEW -> epochDayOf(year - NEW_YEARS) to null
            PickEra.CLASSIC -> null to epochDayOf(year - CLASSIC_YEARS)
        }
    }

    private fun epochDayOf(year: Int): Long = (year - 1970).toLong() * DAYS_PER_YEAR

    private companion object {
        const val QUICK_MAX = 100
        const val EVENING_MAX = 150
        const val NEW_YEARS = 5
        const val CLASSIC_YEARS = 25
        const val DAYS_PER_YEAR = 365L
    }
}
