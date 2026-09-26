package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType

/**
 * One postponed decision (EPIC 42, ADR 0023).
 *
 * The absence of a [com.codingpit.muviss.feature.triage.api.TriageVerdict],
 * not one of them: a [TriageDecision] says the user ruled, this says they
 * declined to and when they want to be asked again. A title never holds both
 * — recording a verdict clears any Snooze on it.
 *
 * Carries the whole card ([title]/[year]/[posterUrl]/[overview]) rather than
 * just an id, because the deck has to render it again on the due date and
 * `MetadataProvider` has no `summary(id)` to rehydrate it cheaply — only
 * `details(id)`, which for a show costs several requests. The cost is that
 * [overview] is frozen in whatever TMDB language was active at the time.
 *
 * [dueAtEpochDay] is an epoch DAY, compared against the same `today` the
 * progress use cases already take, so a title comes back for the whole of its
 * due date in the device's own reckoning rather than at an instant.
 */
data class TriageSnooze(
    val mediaId: MediaId,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val overview: String?,
    val snoozedAtEpochMs: Long,
    val dueAtEpochDay: Long,
) {
    val type: MediaType get() = mediaId.type

    /** Back to the card the deck renders. The snapshot is the whole point of this table. */
    fun toSummary(): MediaSummary = MediaSummary(
        id = mediaId,
        title = title,
        year = year,
        posterUrl = posterUrl,
        overview = overview,
    )

    fun isDueBy(today: Long): Boolean = dueAtEpochDay <= today

    companion object {
        fun of(summary: MediaSummary, nowEpochMs: Long, dueAtEpochDay: Long): TriageSnooze = TriageSnooze(
            mediaId = summary.id,
            title = summary.title,
            year = summary.year,
            posterUrl = summary.posterUrl,
            overview = summary.overview,
            snoozedAtEpochMs = nowEpochMs,
            dueAtEpochDay = dueAtEpochDay,
        )
    }
}
