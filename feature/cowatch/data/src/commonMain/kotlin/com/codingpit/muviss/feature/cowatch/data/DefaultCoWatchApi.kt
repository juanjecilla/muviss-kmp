package com.codingpit.muviss.feature.cowatch.data

import com.codingpit.muviss.feature.cowatch.api.CoWatchApi
import com.codingpit.muviss.feature.cowatch.api.LinkedCompanion
import com.codingpit.muviss.feature.cowatch.api.PoolSettings
import com.codingpit.muviss.feature.cowatch.api.PoolSource
import com.codingpit.muviss.feature.cowatch.api.Shortlist
import com.codingpit.muviss.feature.cowatch.domain.AcceptInviteUseCase
import com.codingpit.muviss.feature.cowatch.domain.CoWatchRepository
import com.codingpit.muviss.feature.cowatch.domain.CreateInviteUseCase
import com.codingpit.muviss.feature.cowatch.domain.ObserveCompanionsUseCase
import com.codingpit.muviss.feature.cowatch.domain.ObserveShortlistUseCase
import com.codingpit.muviss.feature.cowatch.domain.PublishWatchPoolUseCase
import kotlinx.coroutines.flow.Flow

/**
 * Co-watch's [CoWatchApi], assembled from the use cases (EPIC 41).
 *
 * Thin by design, like the other features' api implementations: the decisions
 * live in `:domain`, and this only names them.
 */
internal class DefaultCoWatchApi(
    private val repository: CoWatchRepository,
    private val observeCompanionsUseCase: ObserveCompanionsUseCase,
    private val observeShortlistUseCase: ObserveShortlistUseCase,
    private val createInviteUseCase: CreateInviteUseCase,
    private val acceptInviteUseCase: AcceptInviteUseCase,
    private val publishWatchPoolUseCase: PublishWatchPoolUseCase,
) : CoWatchApi {

    override fun observeCompanions(): Flow<List<LinkedCompanion>> = observeCompanionsUseCase()

    override fun observeShortlist(companionUserId: String): Flow<Shortlist> = observeShortlistUseCase(companionUserId)

    override fun observePoolSettings(): Flow<PoolSettings> = repository.observePoolSettings()

    override suspend fun createInvite(): Result<String> = createInviteUseCase()

    override suspend fun acceptInvite(code: String): Result<Unit> = acceptInviteUseCase(code)

    override suspend fun confirmCompanion(companionUserId: String): Result<Unit> = runCatching { repository.confirmCompanion(companionUserId) }

    override suspend fun setLocalName(companionUserId: String, localName: String?) = repository.setLocalName(companionUserId, localName)

    override suspend fun unlink(companionUserId: String) = repository.unlink(companionUserId)

    override suspend fun setPoolSource(source: PoolSource) {
        repository.setPoolSource(source)
        // The pool is derived from the source, so changing it republishes
        // immediately rather than waiting for something else to notice.
        publishWatchPoolUseCase()
    }

    override suspend fun setIncludeSeenByDefault(include: Boolean) {
        repository.setIncludeSeenByDefault(include)
        publishWatchPoolUseCase()
    }

    override suspend fun refreshPublishedPool() = publishWatchPoolUseCase()
}
