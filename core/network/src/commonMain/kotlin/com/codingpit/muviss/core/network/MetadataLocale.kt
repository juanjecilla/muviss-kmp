package com.codingpit.muviss.core.network

/**
 * The region/language seam discovery calls read from. [language] localizes
 * titles/genre names (TMDB's `language` query param, e.g. "en-US"); [region]
 * (ISO 3166-1 alpha-2, e.g. "US") picks which country's watch providers to
 * surface. Every reader (TmdbProvider, TmdbSearchRepository) re-reads these
 * properties on every call rather than caching them, so whichever
 * implementation is bound always reflects the current value.
 */
interface MetadataLocale {
    val language: String
    val region: String
}

/** Constant locale — used as [com.codingpit.muviss.core.network.tmdb.TmdbProvider]'s default and in tests. */
class DefaultMetadataLocale : MetadataLocale {
    override val language: String = "en-US"
    override val region: String = "US"
}

/**
 * Mutable [MetadataLocale] whose fields can be updated live after
 * construction. Settings (EPIC 8) binds this as the app's `MetadataLocale`
 * singleton (see `com.codingpit.muviss.core.network.di.networkModule`) and
 * pushes the user's saved language/region into it whenever they change —
 * `feature/settings/data`'s `SettingsLocaleSync` is the thing that does the
 * pushing. Everything downstream (TmdbProvider, watch-provider lookups)
 * already reads [language]/[region] fresh on every call, so no restart is
 * needed for a change to take effect.
 */
class MutableMetadataLocale(
    initialLanguage: String = "en-US",
    initialRegion: String = "US",
) : MetadataLocale {
    override var language: String = initialLanguage
    override var region: String = initialRegion
}
