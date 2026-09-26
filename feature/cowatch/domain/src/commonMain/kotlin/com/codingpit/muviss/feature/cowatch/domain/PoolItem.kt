package com.codingpit.muviss.feature.cowatch.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType

/**
 * One title in a Watch Pool, in the shape both sides exchange (EPIC 41).
 *
 * This is the whole of what co-watch knows about a person's library, and the
 * shortness is the point (ADR 0022's third invariant). [started] and [seen] are
 * two bits: they say whether a title is a clean thing to begin together, and
 * nothing about how far, when, or how many times.
 */
data class PoolItem(
    val mediaId: MediaId,
    val mediaType: MediaType,
    val title: String,
    val posterUrl: String?,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val started: Boolean,
    val seen: Boolean,
    val pinned: Boolean,
)
