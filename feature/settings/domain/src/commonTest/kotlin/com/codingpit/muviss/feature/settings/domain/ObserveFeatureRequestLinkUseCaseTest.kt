package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.locale.SystemLocale
import com.codingpit.muviss.core.common.remoteconfig.DefaultsRemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ObserveFeatureRequestLinkUseCaseTest {
    private fun linkFor(deviceTag: String?) = ObserveFeatureRequestLinkUseCase(
        remoteConfig = DefaultsRemoteConfig(RemoteValues(byokEnabled = false, featureRequestUrl = "https://tally.so/r/abc")),
        systemLocale = object : SystemLocale {
            override val languageTag: String? = deviceTag
        },
        appVersion = AppVersion(versionName = "1.0.0", versionCode = 1),
    )

    @Test
    fun locale_is_the_device_locale_that_picks_the_ui_strings() = runTest {
        val link = linkFor("es-ES")().first()
        assertTrue(link.orEmpty().endsWith("&locale=es-ES"), "was $link")
    }

    @Test
    fun locale_falls_back_to_english_when_the_platform_reports_none() = runTest {
        val link = linkFor(null)().first()
        assertTrue(link.orEmpty().endsWith("&locale=en-US"), "was $link")
    }
}
