package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteProfileApi
import com.codingpit.muviss.feature.settings.domain.ObserveTasteProfileUseCase
import com.codingpit.muviss.feature.settings.domain.SetTasteItemUseCase
import kotlinx.coroutines.flow.Flow

/** The [TasteProfileApi] peers depend on: a thin door over the use cases, so the rule lives in one place. */
class DefaultTasteProfileApi(
    private val observeItems: ObserveTasteProfileUseCase,
    private val setItem: SetTasteItemUseCase,
) : TasteProfileApi {
    override fun observe(): Flow<List<TasteItem>> = observeItems()

    override suspend fun setLiked(item: TasteItem, liked: Boolean): Boolean = setItem(item, liked)
}
