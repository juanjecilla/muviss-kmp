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
        // Skip an unchanged value: INSERT OR REPLACE counts as a changed row even when the value is identical,
        // and the SDK rewrites the same config on every fetch. Dialect is SQLite 3.18, so no ON CONFLICT (#897).
        if (queries.selectByKey(key).awaitAsOneOrNull() == value) return
        queries.upsert(key, value)
    }
}
