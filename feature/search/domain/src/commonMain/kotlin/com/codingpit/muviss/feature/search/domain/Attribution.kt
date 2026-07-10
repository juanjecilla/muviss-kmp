package com.codingpit.muviss.feature.search.domain

/**
 * TMDB's watch-provider data (`/movie|tv/{id}/watch/providers`) is sourced
 * from JustWatch; TMDB's API terms require this attribution wherever that
 * data is rendered (https://www.themoviedb.org/documentation/api/terms-of-use).
 * Shown by the Detail screen's "Where to watch" section — see
 * `TMDB_ATTRIBUTION_TEXT` in `feature/settings/domain` for the separate,
 * TMDB-itself attribution.
 */
const val JUSTWATCH_ATTRIBUTION_TEXT: String = "Streaming data provided by JustWatch."
