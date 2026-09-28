package com.codingpit.muviss.feature.settings.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers `resolveLanguage`'s sentinel handling — see issue #136. */
class SupportedLocalesTest {

    @Test
    fun an_explicit_pick_always_wins_over_the_device_locale() {
        val resolved = SupportedLocales.resolveLanguage(storedLanguage = "fr-FR", systemLanguageTag = "es-ES")
        assertEquals("fr-FR", resolved)
    }

    @Test
    fun the_sentinel_resolves_to_an_exact_device_locale_match() {
        val resolved = SupportedLocales.resolveLanguage(
            storedLanguage = SupportedLocales.SYSTEM_DEFAULT_LANGUAGE,
            systemLanguageTag = "es-ES",
        )
        assertEquals("es-ES", resolved)
    }

    @Test
    fun the_sentinel_resolves_by_primary_subtag_when_the_exact_region_is_not_listed() {
        val resolved = SupportedLocales.resolveLanguage(
            storedLanguage = SupportedLocales.SYSTEM_DEFAULT_LANGUAGE,
            systemLanguageTag = "es-MX",
        )
        assertEquals("es-ES", resolved)
    }

    @Test
    fun the_sentinel_falls_back_to_the_default_language_when_unsupported() {
        val resolved = SupportedLocales.resolveLanguage(
            storedLanguage = SupportedLocales.SYSTEM_DEFAULT_LANGUAGE,
            systemLanguageTag = "ru-RU",
        )
        assertEquals(SupportedLocales.DEFAULT_LANGUAGE, resolved)
    }

    @Test
    fun the_sentinel_falls_back_to_the_default_language_when_the_platform_cannot_report_one() {
        val resolved = SupportedLocales.resolveLanguage(
            storedLanguage = SupportedLocales.SYSTEM_DEFAULT_LANGUAGE,
            systemLanguageTag = null,
        )
        assertEquals(SupportedLocales.DEFAULT_LANGUAGE, resolved)
    }
}
