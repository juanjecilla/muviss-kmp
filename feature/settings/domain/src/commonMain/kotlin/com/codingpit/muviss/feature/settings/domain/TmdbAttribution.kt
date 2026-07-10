package com.codingpit.muviss.feature.settings.domain

/**
 * TMDB requires this attribution to be shown wherever TMDB-sourced data is
 * displayed (their terms of use also require their logo alongside it —
 * https://www.themoviedb.org/documentation/api/terms-of-use — not rendered
 * yet: no TMDB logo asset ships with the app today).
 *
 * Surfaced on the Settings "About" screen (see `SettingsScreen`'s About
 * section). Also tracked in docs/store/DATA_SAFETY.md and docs/PRIVACY.md —
 * do not remove this constant without moving the text there first.
 */
const val TMDB_ATTRIBUTION_TEXT: String =
    "This product uses the TMDB API but is not endorsed or certified by TMDB."
