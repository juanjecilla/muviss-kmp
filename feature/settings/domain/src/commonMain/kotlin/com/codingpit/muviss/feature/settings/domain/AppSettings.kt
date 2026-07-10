package com.codingpit.muviss.feature.settings.domain

/** The full set of user-configurable app settings (see [SettingsRepository]). */
data class AppSettings(
    val theme: AppTheme = AppTheme.SYSTEM,
    val language: String = SupportedLocales.DEFAULT_LANGUAGE,
    val region: String = SupportedLocales.DEFAULT_REGION,
    val notificationsEnabled: Boolean = true,
)
