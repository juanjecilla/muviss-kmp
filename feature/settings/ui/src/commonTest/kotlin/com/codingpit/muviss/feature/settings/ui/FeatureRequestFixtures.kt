package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.locale.SystemLocale
import com.codingpit.muviss.core.common.remoteconfig.DefaultsRemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import com.codingpit.muviss.feature.settings.domain.ObserveFeatureRequestLinkUseCase
import com.codingpit.muviss.feature.settings.domain.ObserveSettingsUseCase
import com.codingpit.muviss.feature.settings.domain.SettingsObservations

/** Settings observations over [observeSettings]; the Feature Request link is defaults-only (no form, so no row) unless [remote] says otherwise. */
internal fun settingsObservations(
    observeSettings: ObserveSettingsUseCase,
    remote: RemoteConfig = DefaultsRemoteConfig(RemoteValues(byokEnabled = false)),
) = SettingsObservations(
    settings = observeSettings,
    featureRequestLink = ObserveFeatureRequestLinkUseCase(
        remoteConfig = remote,
        systemLocale = object : SystemLocale {
            override val languageTag: String? = "en-US"
        },
        appVersion = AppVersion(versionName = "1.0.0", versionCode = 1),
    ),
)
