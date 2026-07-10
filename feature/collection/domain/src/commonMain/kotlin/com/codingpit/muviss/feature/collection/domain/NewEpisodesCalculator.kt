package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId

/**
 * One saved show/movie whose aired-episode count went up since [before], the
 * input to EPIC 5's Android notification. [latestEpisodeLabel] is the
 * "SxxExx" label of the newest aired episode (null for a movie, where the
 * notification text is just "<title> is out").
 */
data class NewEpisodeNotification(
    val mediaId: MediaId,
    val title: String,
    val newEpisodeCount: Int,
    val latestEpisodeLabel: String?,
)

/**
 * Pure diff: compares each saved title's stored [CollectionEntry.airedEpisodes]
 * ([before], read prior to a snapshot refresh) against its freshly re-fetched
 * [MediaDetails] ([after]) and reports the ones whose aired count increased.
 *
 * Free of any I/O, coroutine, or platform dependency — [after] usually comes
 * from re-running [MediaSnapshotSource.fetch] per entry (see
 * `RefreshAndFindNewEpisodesUseCase`), but a test can hand-build both lists
 * directly. [mutedMediaIds] excludes shows the user has silenced
 * individually (`CollectionEntry.notificationsMuted`); the global
 * notifications toggle (`SettingsRepository.notificationsEnabled`) is a
 * separate concern the caller checks before invoking this at all.
 */
object NewEpisodesCalculator {
    fun diff(
        before: List<CollectionEntry>,
        after: List<MediaDetails>,
        mutedMediaIds: Set<MediaId>,
        todayEpochDay: Long,
    ): List<NewEpisodeNotification> {
        val beforeByMediaId = before.associateBy { it.mediaId }
        return after.mapNotNull { details ->
            val mediaId = details.id
            if (mediaId in mutedMediaIds) return@mapNotNull null

            val previous = beforeByMediaId[mediaId] ?: return@mapNotNull null
            val newAiredCount = details.airedEpisodeCount(todayEpochDay)
            val newEpisodeCount = newAiredCount - previous.airedEpisodes
            if (newEpisodeCount <= 0) return@mapNotNull null

            NewEpisodeNotification(
                mediaId = mediaId,
                title = details.summary.title,
                newEpisodeCount = newEpisodeCount,
                latestEpisodeLabel = details.latestAiredEpisodeLabel(todayEpochDay),
            )
        }
    }
}
