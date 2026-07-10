package com.codingpit.muviss.feature.settings.domain

/** One TMDB request-language option, e.g. `es-ES` displayed as "Español (España)". */
data class SupportedLanguage(val code: String, val displayName: String)

/** One watch-provider region option (ISO 3166-1 alpha-2), e.g. `US` displayed as "United States". */
data class SupportedRegion(val code: String, val displayName: String)

/**
 * The curated language/region lists the settings pickers offer. Deliberately
 * a short, sensible list rather than TMDB's full catalog — anyone needing a
 * language not listed can be added here without touching the picker UI.
 */
object SupportedLocales {
    const val DEFAULT_LANGUAGE: String = "en-US"
    const val DEFAULT_REGION: String = "US"

    val languages: List<SupportedLanguage> = listOf(
        SupportedLanguage("en-US", "English (US)"),
        SupportedLanguage("es-ES", "Español (España)"),
        SupportedLanguage("fr-FR", "Français"),
        SupportedLanguage("de-DE", "Deutsch"),
        SupportedLanguage("it-IT", "Italiano"),
        SupportedLanguage("pt-BR", "Português (Brasil)"),
        SupportedLanguage("ja-JP", "日本語"),
    )

    val regions: List<SupportedRegion> = listOf(
        SupportedRegion("US", "United States"),
        SupportedRegion("ES", "Spain"),
        SupportedRegion("GB", "United Kingdom"),
        SupportedRegion("FR", "France"),
        SupportedRegion("DE", "Germany"),
        SupportedRegion("IT", "Italy"),
        SupportedRegion("BR", "Brazil"),
        SupportedRegion("JP", "Japan"),
    )
}
