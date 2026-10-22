package com.codingpit.muviss.core.remoteconfig.di

import com.codingpit.muviss.core.common.remoteconfig.DefaultsRemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.FeatureRequestProvider
import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.remoteconfig.ConfigCatRemoteConfig
import com.codingpit.muviss.core.remoteconfig.MuvissBuildConfig
import com.codingpit.muviss.core.remoteconfig.SqlDelightConfigCache
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Binds [RemoteConfig]. A blank `CONFIGCAT_SDK_KEY` binds the defaults-only
 * implementation, which makes no request: that absence is what keeps a local or
 * CI build from contacting the vendor (ADR 0026, same "key present or not"
 * branch as sync, ADR 0007).
 *
 * The SDK is handed the app's own engine (`HttpClient.engine`) — its POM only
 * brings the Darwin one, so otherwise Android and the JVM would pick an engine
 * of the SDK's choosing.
 */
val remoteConfigModule: Module = module {
    single<RemoteConfig> {
        val defaults = RemoteValues(
            byokEnabled = MuvissBuildConfig.BYOK_ENABLED,
            featureRequestProvider = FeatureRequestProvider.fromStored(MuvissBuildConfig.FEATURE_REQUEST_PROVIDER),
            featureRequestUrl = MuvissBuildConfig.FEATURE_REQUEST_URL.takeIf { it.isNotBlank() },
        )
        if (MuvissBuildConfig.CONFIGCAT_SDK_KEY.isBlank()) {
            DefaultsRemoteConfig(defaults)
        } else {
            ConfigCatRemoteConfig(
                sdkKey = MuvissBuildConfig.CONFIGCAT_SDK_KEY,
                engine = get<HttpClient>().engine,
                cache = SqlDelightConfigCache(get<MuvissDatabase>().remoteConfigCacheQueries),
                defaults = defaults,
                clock = get(),
            )
        }
    }
}
