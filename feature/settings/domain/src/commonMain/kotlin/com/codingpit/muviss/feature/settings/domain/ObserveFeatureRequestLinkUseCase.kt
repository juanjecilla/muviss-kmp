package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.locale.SystemLocale
import com.codingpit.muviss.core.common.platform.platformName
import com.codingpit.muviss.core.common.platform.platformOsVersion
import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The Feature Request link for Settings, or null while there is nothing to open (no form
 * yet, or the provider is off). Follows Remote Config, so moving or switching the form
 * needs no release.
 *
 * `locale` is the device locale, which is what picks the app's UI strings (English where
 * Muviss has no translation for it). It is deliberately not the Settings language: that
 * one only chooses the language of TMDB requests, so a user reading the app in Spanish
 * with English titles would otherwise be reported as `en-US`.
 */
class ObserveFeatureRequestLinkUseCase(
    private val remoteConfig: RemoteConfig,
    private val systemLocale: SystemLocale,
    private val appVersion: AppVersion,
) {
    operator fun invoke(): Flow<String?> = remoteConfig.values.map { remote ->
        FeatureRequestLink.build(
            provider = remote.featureRequestProvider,
            baseUrl = remote.featureRequestUrl,
            context = FeatureRequestContext(
                version = appVersion.versionName,
                platform = platformName,
                os = platformOsVersion,
                locale = systemLocale.languageTag ?: SupportedLocales.DEFAULT_LANGUAGE,
            ),
        )
    }.distinctUntilChanged()
}
