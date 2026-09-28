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

    /**
     * The stored [AppSettings.language] value that means "System default"
     * rather than an explicit pick (issue #136) — empty so an install that
     * never touched the picker resolves from the device locale, while a
     * real language code (including a deliberate "en-US") always wins over
     * it. `SqlDelightSettingsRepository`'s `ensureRow` seeds new rows with
     * this; existing rows keep whatever they already had (never migrated —
     * the schema can't tell "never touched" from "chose en-US on purpose").
     */
    const val SYSTEM_DEFAULT_LANGUAGE: String = ""

    val languages: List<SupportedLanguage> = listOf(
        SupportedLanguage("en-US", "English (US)"),
        SupportedLanguage("es-ES", "Español (España)"),
        SupportedLanguage("fr-FR", "Français"),
        SupportedLanguage("de-DE", "Deutsch"),
        SupportedLanguage("it-IT", "Italiano"),
        SupportedLanguage("pt-BR", "Português (Brasil)"),
        SupportedLanguage("ja-JP", "日本語"),
    )

    /**
     * Resolves the TMDB request language a stored [AppSettings.language]
     * value should use. An explicit pick (anything but [SYSTEM_DEFAULT_LANGUAGE])
     * always wins unchanged. The sentinel resolves against [systemLanguageTag]
     * (from `:core:common`'s `SystemLocale`, read live): an exact match in
     * [languages] wins, then a same-primary-subtag match (e.g. device "es-MX"
     * matches listed "es-ES"), and [DEFAULT_LANGUAGE] when nothing matches or
     * the platform couldn't report a locale.
     */
    fun resolveLanguage(storedLanguage: String, systemLanguageTag: String?): String {
        if (storedLanguage != SYSTEM_DEFAULT_LANGUAGE) return storedLanguage
        if (systemLanguageTag == null) return DEFAULT_LANGUAGE

        val exact = languages.firstOrNull { it.code.equals(systemLanguageTag, ignoreCase = true) }
        if (exact != null) return exact.code

        val primary = systemLanguageTag.substringBefore('-')
        val byPrimary = languages.firstOrNull { it.code.substringBefore('-').equals(primary, ignoreCase = true) }
        return byPrimary?.code ?: DEFAULT_LANGUAGE
    }

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
