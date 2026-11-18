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

/**
 * The Image Viewer: one title's, or one episode's, artwork, full screen. [kind] is an
 * [com.codingpit.muviss.models.ImageKind] name; [seedUrl] is the image that was tapped, shown at
 * once while the rest of the Gallery loads. [episodeId] is set for an episode's stills.
 */
@Serializable
data class ImageViewerRoute(val mediaId: String, val kind: String, val seedUrl: String, val episodeId: String? = null)

/** One person's page; carries the [com.codingpit.muviss.models.PersonId] as its string form. */
@Serializable
data class PersonRoute(val personId: String)
