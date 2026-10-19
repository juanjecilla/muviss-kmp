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
 * Where Settings' two "talk to us" rows open (ADR 0034): the Feature Request form and the
 * Bug Report form. A null link means there is nothing to open yet (no form, or the provider
 * is off), which hides that row.
 */
data class FeedbackLinks(val featureRequest: String? = null, val bugReport: String? = null)

/**
 * The [FeedbackLinks] for Settings. Follows Remote Config, so moving or switching a form needs
 * no release, and follows the app language so a report says which one the user actually reads
 * the app in. Both forms carry the same four hidden fields ([FeatureRequestContext]), so a bug
 * report adds nothing to what the privacy policy already says.
 */
class ObserveFeedbackLinksUseCase(
    private val remoteConfig: RemoteConfig,
    private val observeSettings: ObserveSettingsUseCase,
    private val systemLocale: SystemLocale,
    private val appVersion: AppVersion,
) {
    operator fun invoke(): Flow<FeedbackLinks> = combine(remoteConfig.values, observeSettings()) { remote, settings ->
        val context = FeatureRequestContext(
            version = appVersion.versionName,
            platform = platformName,
            os = platformOsVersion,
            locale = SupportedLocales.resolveLanguage(settings.language, systemLocale.languageTag),
        )
        FeedbackLinks(
            featureRequest = FeatureRequestLink.build(remote.featureRequestProvider, remote.featureRequestUrl, context),
            bugReport = FeatureRequestLink.build(remote.featureRequestProvider, remote.bugReportUrl, context),
        )
    }.distinctUntilChanged()
}
