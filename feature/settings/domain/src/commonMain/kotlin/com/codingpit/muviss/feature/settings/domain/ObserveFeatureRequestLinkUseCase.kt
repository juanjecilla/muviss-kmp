package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.locale.SystemLocale
import com.codingpit.muviss.core.common.platform.platformName
import com.codingpit.muviss.core.common.platform.platformOsVersion
import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The Feature Request link for Settings, or null while there is nothing to open (no form
 * yet, or the provider is off). Follows Remote Config, so moving or switching the form
 * needs no release, and follows the app language so the request says which one the user
 * actually reads the app in.
 */
class ObserveFeatureRequestLinkUseCase(
    private val remoteConfig: RemoteConfig,
    private val observeSettings: ObserveSettingsUseCase,
    private val systemLocale: SystemLocale,
    private val appVersion: AppVersion,
) {
    operator fun invoke(): Flow<String?> = combine(remoteConfig.values, observeSettings()) { remote, settings ->
        FeatureRequestLink.build(
            provider = remote.featureRequestProvider,
            baseUrl = remote.featureRequestUrl,
            context = FeatureRequestContext(
                version = appVersion.versionName,
                platform = platformName,
                os = platformOsVersion,
                locale = SupportedLocales.resolveLanguage(settings.language, systemLocale.languageTag),
            ),
        )
    }.distinctUntilChanged()
}
