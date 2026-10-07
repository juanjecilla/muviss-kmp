package com.codingpit.muviss.notifications

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.SourceId

/**
 * Notification ids that cannot collide (EPIC 30, #73).
 *
 * They used to be `mediaId.toString().hashCode()`: two shows could share an id
 * and one notification silently replaced the other, and a show could even
 * land on [SUMMARY] (0) and replace the group summary. A TMDB id is a small
 * positive integer, unique per media type, so `2n + typeBit + 1` is distinct
 * for every title and never 0. A title from another source falls back to a
 * hash kept negative, so it can collide only with another non-TMDB title,
 * never with a TMDB one or the summary.
 */
internal object NotificationIds {
    const val SUMMARY = 0

    fun forTitle(mediaId: MediaId): Int {
        val tmdb = mediaId.external.toIntOrNull()?.takeIf { mediaId.source == SourceId.TMDB && it in 0..MAX_TMDB_ID }
        return if (tmdb != null) {
            tmdb * 2 + (if (mediaId.type == MediaType.TV) 1 else 0) + 1
        } else {
            -(mediaId.toString().hashCode() and Int.MAX_VALUE) - 1
        }
    }

    /** Keeps `2n + 2` inside an Int. */
    private const val MAX_TMDB_ID = (Int.MAX_VALUE - 2) / 2
}
