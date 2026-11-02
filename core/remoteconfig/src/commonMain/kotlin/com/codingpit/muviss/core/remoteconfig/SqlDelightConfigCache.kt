package com.codingpit.muviss.core.remoteconfig

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.database.RemoteConfigCacheQueries
import com.configcat.ConfigCache

/**
 * ConfigCat's persistent cache, in the app's own database (`remoteConfigCache`,
 * see `RemoteConfigCache.sq`). The SDK's default cache is empty on Android and
 * the JVM, so without this the last good config would not survive a restart on
 * two of the six targets — and a restart is exactly when a rotated TMDB
 * credential has to apply.
 */
internal class SqlDelightConfigCache(private val queries: RemoteConfigCacheQueries) : ConfigCache {
    override suspend fun read(key: String): String? = queries.selectByKey(key).awaitAsOneOrNull()

    override suspend fun write(key: String, value: String) {
        queries.upsert(key, value)
    }
}
