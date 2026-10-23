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
 * Where Settings' two "talk to us" rows open (ADR 0035): the Feature Request form and the
 * Bug Report form. A null link means there is nothing to open yet (no form, or the provider
 * is off), which hides that row.
 */
data class FeedbackLinks(val featureRequest: String? = null, val bugReport: String? = null)

/**
 * The [FeedbackLinks] for Settings. Follows Remote Config, so moving or switching a form needs
 * no release. Both forms carry the same four hidden fields ([FeatureRequestContext]), so a bug
 * report adds nothing to what the privacy policy already says.
 *
 * `locale` is the device locale, which is what picks the app's UI strings (English where
 * Muviss has no translation for it). It is deliberately not the Settings language: that
 * one only chooses the language of TMDB requests, so a user reading the app in Spanish
 * with English titles would otherwise be reported as `en-US`.
 */
class ObserveFeedbackLinksUseCase(
    private val remoteConfig: RemoteConfig,
    private val systemLocale: SystemLocale,
    private val appVersion: AppVersion,
) {
    operator fun invoke(): Flow<FeedbackLinks> = remoteConfig.values.map { remote ->
        val context = FeatureRequestContext(
            version = appVersion.versionName,
            platform = platformName,
            os = platformOsVersion,
            locale = systemLocale.languageTag ?: SupportedLocales.DEFAULT_LANGUAGE,
        )
        FeedbackLinks(
            featureRequest = FeatureRequestLink.build(remote.featureRequestProvider, remote.featureRequestUrl, context),
            bugReport = FeatureRequestLink.build(remote.featureRequestProvider, remote.bugReportUrl, context),
        )
    }.distinctUntilChanged()
}
