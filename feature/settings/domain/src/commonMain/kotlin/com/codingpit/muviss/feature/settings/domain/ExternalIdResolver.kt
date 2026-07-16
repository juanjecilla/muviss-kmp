package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType

/**
 * Resolves an import row's [ExternalTitleRef] to a real [MediaId], or null
 * when it can't be placed (no ids on the row, or TMDB has no match). The
 * data-layer implementation ([com.codingpit.muviss.feature.settings.data]'s
 * `TmdbExternalIdResolver`) maps a TMDB id directly (no network call) and
 * goes through `MetadataProvider.findByExternalId` for an IMDb id — this
 * interface exists so [PreviewImportUseCase] stays free of `:core:network`.
 */
interface ExternalIdResolver {
    suspend fun resolve(ref: ExternalTitleRef, type: MediaType?): MediaId?
}
