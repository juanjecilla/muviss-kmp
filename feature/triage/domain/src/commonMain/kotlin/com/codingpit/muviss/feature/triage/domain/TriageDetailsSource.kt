package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId

/**
 * Fetches the full season/episode structure a verdict needs before it can be
 * saved. The deck itself only ever holds `MediaSummary`, so this is the one
 * network call a verdict pays for — and only the three verdicts that save.
 *
 * Deliberately triage's own seam rather than collection's internal
 * `MediaSnapshotSource` (ADR 0004 keeps that module internal), mirroring how
 * settings owns `ImportMediaDetailsSource` for the same reason.
 */
interface TriageDetailsSource {
    suspend fun fetch(mediaId: MediaId): Result<MediaDetails>
}
