package com.codingpit.muviss.feature.search.ui

import kotlinx.serialization.Serializable

/** Entry destination for the search tab. */
@Serializable
data object SearchRoute

/** Detail destination; carries the [com.codingpit.muviss.models.MediaId] as its string form. */
@Serializable
data class DetailRoute(val mediaId: String)

/** One episode's own page; carries the [com.codingpit.muviss.models.EpisodeId] as its string form. */
@Serializable
data class EpisodeDetailRoute(val episodeId: String)
