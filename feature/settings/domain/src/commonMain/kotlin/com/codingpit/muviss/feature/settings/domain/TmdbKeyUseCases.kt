package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.feature.settings.api.SaveTmdbKeyResult
import com.codingpit.muviss.feature.settings.api.TmdbDataSource
import com.codingpit.muviss.feature.settings.api.TmdbKeyApi
import kotlinx.coroutines.flow.Flow

class ObserveTmdbDataSourceUseCase(private val settings: TmdbKeyApi) {
    operator fun invoke(): Flow<TmdbDataSource> = settings.observeSource()
}

class SaveTmdbKeyUseCase(private val settings: TmdbKeyApi) {
    suspend operator fun invoke(raw: String): SaveTmdbKeyResult = settings.save(raw)
}

class ClearTmdbKeyUseCase(private val settings: TmdbKeyApi) {
    suspend operator fun invoke() = settings.clear()
}
