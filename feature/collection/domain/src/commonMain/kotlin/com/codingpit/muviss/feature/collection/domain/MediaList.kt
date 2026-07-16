package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.MediaId

/**
 * A user-defined list (EPIC 17, TV Time parity — e.g. "Marathon 2026").
 * Orthogonal to [CollectionEntry]/`WatchStatus` (ADR 0005): a title can be
 * saved to the library, a member of any number of lists, both, or neither,
 * independently.
 *
 * [entryCount] only counts entries with a live library snapshot to render —
 * see [ListsRepository]'s KDoc for the orphaned-entry rule this reflects.
 */
data class MediaList(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val entryCount: Int = 0,
)

/**
 * One rendered row of a [MediaList]'s contents: a `listEntry` membership
 * joined with its title's `collectionEntry` snapshot (title/poster) — see
 * [ListsRepository.observeListContents]. Rows whose title has no live
 * snapshot are not represented here at all (they're hidden, not
 * null-filled); see the repository's KDoc.
 */
data class MediaListItem(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val addedAtEpochMs: Long,
)
