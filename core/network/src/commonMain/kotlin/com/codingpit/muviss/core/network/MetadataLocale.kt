package com.codingpit.muviss.core.network

/**
 * The region/language seam discovery calls read from. [language] localizes
 * genre names (TMDB's `language` query param, e.g. "en-US"); [region] (ISO
 * 3166-1 alpha-2, e.g. "ES") picks which country's watch providers to
 * surface. [DefaultMetadataLocale] is a constant stand-in until settings
 * (EPIC 8) backs this with a real user preference — only the Koin binding in
 * [com.codingpit.muviss.core.network.di.networkModule] changes then, nothing
 * that reads [MetadataLocale].
 */
interface MetadataLocale {
    val language: String
    val region: String
}

/** Constant locale used until real settings land (see [MetadataLocale]). */
class DefaultMetadataLocale : MetadataLocale {
    override val language: String = "en-US"
    override val region: String = "ES"
}
