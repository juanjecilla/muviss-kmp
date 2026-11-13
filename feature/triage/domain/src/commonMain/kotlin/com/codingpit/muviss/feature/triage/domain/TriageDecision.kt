package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary

/**
 * One recorded verdict (ADR 0010). Carries its own [title]/[posterUrl]
 * because a [TriageVerdict.SKIP] leaves no `CollectionEntry` to read them
 * from — the Skipped screen renders straight off these rows.
 *
 * [createdEntry] is true only when this verdict is the one that put the title
 * in the library (#767). Undo removes the library entry only then, so a title
 * the person added while the deck was open is not taken back with the verdict.
 * It stays false for a verdict that saves nothing.
 *
 * [resolved] is false while the verdict's side effects (fetch details, save to
 * the collection, write ticks) have not finished. The decision itself is
 * always written first and always sticks: dedupe must survive a failed
 * network call, otherwise a flaky connection turns into the deck asking again.
 */
data class TriageDecision(
    val mediaId: MediaId,
    val verdict: TriageVerdict,
    val title: String,
    val posterUrl: String?,
    val decidedAtEpochMs: Long,
    val resolved: Boolean = true,
    val createdEntry: Boolean = false,
) {
    companion object {
        fun of(
            summary: MediaSummary,
            verdict: TriageVerdict,
            nowEpochMs: Long,
            resolved: Boolean,
            createdEntry: Boolean = false,
        ): TriageDecision = TriageDecision(
            mediaId = summary.id,
            verdict = verdict,
            title = summary.title,
            posterUrl = summary.posterUrl,
            decidedAtEpochMs = nowEpochMs,
            resolved = resolved,
            createdEntry = createdEntry,
        )
    }
}
