package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId

/**
 * Fetches the full [MediaDetails] snapshot for a resolved import row, the
 * same shape [ApplyImportUseCase] hands to `CollectionApi.add`. A settings-
 * feature-owned mirror of collection:domain's `MediaSnapshotSource` — kept
 * separate rather than shared because ADR 0004 only allows depending on a
 * peer's `:api`, and this fetch is settings' own concern, not collection's.
 */
interface ImportMediaDetailsSource {
    suspend fun fetch(mediaId: MediaId): Result<MediaDetails>
}
