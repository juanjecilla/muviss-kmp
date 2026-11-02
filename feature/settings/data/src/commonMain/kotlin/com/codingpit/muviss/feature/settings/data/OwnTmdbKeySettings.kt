package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.core.network.tmdb.OwnTmdbKey
import com.codingpit.muviss.core.network.tmdb.SaveOwnKeyResult
import com.codingpit.muviss.core.network.tmdb.TmdbKeySource
import com.codingpit.muviss.feature.settings.api.SaveTmdbKeyResult
import com.codingpit.muviss.feature.settings.api.TmdbDataSource
import com.codingpit.muviss.feature.settings.api.TmdbKeyApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [TmdbKeyApi] over `:core:network`'s [OwnTmdbKey]; the key stays in its [com.codingpit.muviss.core.common.secrets.SecretStore]. */
class OwnTmdbKeySettings(private val ownKey: OwnTmdbKey) : TmdbKeyApi {

    override fun observeSource(): Flow<TmdbDataSource> = ownKey.observeSource().map {
        when (it) {
            TmdbKeySource.MUVISS -> TmdbDataSource.MUVISS
            TmdbKeySource.YOURS -> TmdbDataSource.YOURS
        }
    }

    override suspend fun save(raw: String): SaveTmdbKeyResult = when (ownKey.save(raw)) {
        SaveOwnKeyResult.Saved -> SaveTmdbKeyResult.SAVED
        SaveOwnKeyResult.Malformed -> SaveTmdbKeyResult.MALFORMED
        SaveOwnKeyResult.Rejected -> SaveTmdbKeyResult.REJECTED
        SaveOwnKeyResult.CouldNotCheck -> SaveTmdbKeyResult.COULD_NOT_CHECK
    }

    override suspend fun clear() = ownKey.clear()
}
