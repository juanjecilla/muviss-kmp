package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.core.common.civilDateOf
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickEra
import com.codingpit.muviss.feature.nextpick.api.PickRuntime

/**
 * The seam an AI ranker would replace (ADR 0032, spike #404): criteria and candidates in, an ordered
 * list with reasons out. Sources, filters, exclusions and the screens do not change when it does.
 */
fun interface PickRanker {
    fun rank(criteria: PickCriteria, candidates: List<PickCandidate>, history: PickHistory): List<RankedPick>
}

/**
 * Rules over the person's own data, no model (ADR 0032). A scored sum, each term named so the reason
 * for a pick is the term that contributed most. Weights are constants here and nowhere else: they are
 * a starting point to tune by looking at real picks, not a measured truth.
 */
class RuleBasedRanker : PickRanker {

    private class Term(val score: Double, val reason: PickReason)

    override fun rank(criteria: PickCriteria, candidates: List<PickCandidate>, history: PickHistory): List<RankedPick> = candidates
        .map { candidate ->
            val terms = termsFor(criteria, candidate, history)
            val best = terms.maxByOrNull { it.score }?.takeIf { it.score > 0 }
            RankedPick(candidate, terms.sumOf { it.score }, best?.reason ?: PickReason.FitsWhatYouAsked)
        }
        .sortedWith(compareByDescending<RankedPick> { it.score }.thenBy { it.candidate.summary.title }.thenBy { it.candidate.id.toString() })

    private fun termsFor(criteria: PickCriteria, c: PickCandidate, h: PickHistory): List<Term> = buildList {
        mood(criteria, c)?.let(::add)
        anchor(criteria, c, h)?.let(::add)
        genreAffinity(c, h)?.let(::add)
        if (c.favorite) add(Term(FAVORITE, PickReason.YourFavorite))
        c.addedAtEpochMs?.let { added -> if (c.source == CandidateSource.BACKLOG && h.todayEpochDay > 0) staleBacklog(added, h)?.let(::add) }
        c.summary.rating?.let { add(Term(((it - NEUTRAL_VOTE) / VOTE_SPREAD).coerceIn(-1.0, MAX_VOTE_BONUS), PickReason.HighlyRated(it))) }
        runtime(criteria, c)?.let(::add)
        era(criteria, c, h)?.let(::add)
        language(criteria, c)?.let(::add)
    }

    private fun mood(criteria: PickCriteria, c: PickCandidate): Term? {
        if (criteria.moods.isEmpty()) return null
        val matched = criteria.moods.firstOrNull { mood -> c.summary.genreIds.any { it in MoodGenres.genreIds(setOf(mood), c.summary.type) } }
        return Term(if (matched != null) MOOD_MATCH else MOOD_MISS, matched?.let(PickReason::FitsMood) ?: PickReason.FitsWhatYouAsked)
    }

    private fun anchor(criteria: PickCriteria, c: PickCandidate, h: PickHistory): Term? {
        val anchor = criteria.anchor ?: return null
        val own = c.summary.genreIds.toSet()
        val overlap = if (own.isEmpty() || h.anchorGenreIds.isEmpty()) 0.0 else own.intersect(h.anchorGenreIds).size.toDouble() / own.union(h.anchorGenreIds).size
        val fromAnchor = c.source == CandidateSource.RECOMMENDED || c.source == CandidateSource.SIMILAR
        return if (anchor.like) {
            Term(overlap * ANCHOR_OVERLAP + if (fromAnchor) ANCHOR_SOURCE else 0.0, PickReason.LikeAnchor(h.anchorTitle))
        } else {
            Term(-overlap * ANCHOR_OVERLAP, PickReason.UnlikeAnchor(h.anchorTitle))
        }
    }

    private fun genreAffinity(c: PickCandidate, h: PickHistory): Term? {
        val rated = c.summary.genreIds.mapNotNull { id -> h.genreRatings[id]?.let { id to it } }
        if (rated.isEmpty()) return null
        val best = rated.maxBy { it.second }
        val mean = rated.map { it.second }.average()
        return Term((mean - NEUTRAL_RATING) / RATING_SPREAD * AFFINITY, PickReason.YouRateTheseGenresHigh(best.first, best.second))
    }

    private fun staleBacklog(addedAtEpochMs: Long, h: PickHistory): Term? {
        val days = h.todayEpochDay - addedAtEpochMs / DAY_MS
        return if (days >= STALE_DAYS) Term(STALE_BONUS, PickReason.LongInYourList) else null
    }

    private fun runtime(criteria: PickCriteria, c: PickCandidate): Term? {
        val wanted = criteria.runtime ?: return null
        val minutes = c.runtimeMinutes ?: return null
        val range = when (wanted) {
            PickRuntime.QUICK -> 0..QUICK_MAX
            PickRuntime.EVENING -> 0..EVENING_MAX
            PickRuntime.BINGE -> (EVENING_MAX + 1)..Int.MAX_VALUE
        }
        return Term(if (minutes in range) RUNTIME_FIT else RUNTIME_MISS, PickReason.FitsTime(minutes))
    }

    private fun era(criteria: PickCriteria, c: PickCandidate, h: PickHistory): Term? {
        val era = criteria.era ?: return null
        val year = c.summary.year ?: return null
        val thisYear = if (h.todayEpochDay > 0) civilDateOf(h.todayEpochDay).year else return null
        return when (era) {
            PickEra.NEW -> Term(if (year >= thisYear - NEW_YEARS) ERA_FIT else ERA_MISS, PickReason.RecentRelease)
            PickEra.CLASSIC -> Term(if (year <= thisYear - CLASSIC_YEARS) ERA_FIT else ERA_MISS, PickReason.Classic)
        }
    }

    private fun language(criteria: PickCriteria, c: PickCandidate): Term? {
        val wanted = criteria.originalLanguage ?: return null
        val own = c.originalLanguage ?: return null
        return Term(if (own == wanted) LANGUAGE_FIT else LANGUAGE_MISS, PickReason.InYourLanguage(wanted))
    }

    private companion object {
        const val MOOD_MATCH = 3.0
        const val MOOD_MISS = -1.0
        const val ANCHOR_OVERLAP = 4.0
        const val ANCHOR_SOURCE = 2.0
        const val AFFINITY = 2.0
        const val NEUTRAL_RATING = 6.0
        const val RATING_SPREAD = 3.0
        const val FAVORITE = 2.0
        const val STALE_DAYS = 90
        const val STALE_BONUS = 0.5
        const val DAY_MS = 86_400_000L
        const val NEUTRAL_VOTE = 6.5
        const val VOTE_SPREAD = 2.0
        const val MAX_VOTE_BONUS = 1.5
        const val QUICK_MAX = 100
        const val EVENING_MAX = 150
        const val RUNTIME_FIT = 1.0
        const val RUNTIME_MISS = -2.0
        const val NEW_YEARS = 5
        const val CLASSIC_YEARS = 25
        const val ERA_FIT = 1.0
        const val ERA_MISS = -1.0
        const val LANGUAGE_FIT = 1.0
        const val LANGUAGE_MISS = -1.0
    }
}
