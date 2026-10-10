package com.codingpit.muviss.feature.cowatch.ui

import com.codingpit.muviss.feature.cowatch.api.CoWatchApi
import com.codingpit.muviss.feature.cowatch.api.CompanionState
import com.codingpit.muviss.feature.cowatch.api.LinkedCompanion
import com.codingpit.muviss.feature.cowatch.api.PoolSettings
import com.codingpit.muviss.feature.cowatch.api.PoolSource
import com.codingpit.muviss.feature.cowatch.api.Shortlist
import com.codingpit.muviss.feature.cowatch.api.ShortlistItem
import com.codingpit.muviss.feature.cowatch.api.ShortlistReason
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal fun companion(
    userId: String,
    state: CompanionState = CompanionState.ACTIVE,
    localName: String? = null,
    poolPublishedAtEpochMs: Long? = null,
) = LinkedCompanion(userId, localName, state, poolPublishedAtEpochMs)

@Suppress("LongParameterList") // a row builder: one named default per field, so a case overrides only what it asserts on
internal fun shortlistItem(
    id: String,
    title: String = "Title $id",
    mediaType: MediaType = MediaType.MOVIE,
    posterUrl: String? = null,
    runtimeMinutes: Int? = 100,
    reasons: Set<ShortlistReason> = emptySet(),
) = ShortlistItem(
    mediaId = if (mediaType == MediaType.MOVIE) MediaId.tmdbMovie(id) else MediaId.tmdbTv(id),
    mediaType = mediaType,
    title = title,
    posterUrl = posterUrl,
    runtimeMinutes = runtimeMinutes,
    reasons = reasons,
)

/**
 * Stands in for [CoWatchApi] over mutable in-memory state, so a screen test can
 * drive it the way the real backend would: companions and settings are read
 * flows, a Shortlist is keyed by the Companion it was requested for, and every
 * mutating call is both recorded (for assertions) and reflected back into the
 * flows (so the screen under test sees its own actions the way it would live).
 */
internal class FakeCoWatchApi(
    companions: List<LinkedCompanion> = emptyList(),
    settings: PoolSettings = PoolSettings(PoolSource.NotStarted, includeSeenByDefault = true),
) : CoWatchApi {

    val companions = MutableStateFlow(companions)
    val settings = MutableStateFlow(settings)

    /** Keyed by companion user id. A companion with no entry has never published. */
    val shortlists = MutableStateFlow<Map<String, Shortlist>>(emptyMap())

    var createInviteResult: Result<String> = Result.success("ABCD-1234")
    var acceptInviteResult: Result<Unit> = Result.success(Unit)

    val acceptedCodes = mutableListOf<String>()
    val confirmed = mutableListOf<String>()
    val renamed = mutableListOf<Pair<String, String?>>()
    val unlinked = mutableListOf<String>()
    val poolSourcesSet = mutableListOf<PoolSource>()
    val includeSeenSet = mutableListOf<Boolean>()
    var refreshPublishedPoolCalls = 0

    override fun observeCompanions(): Flow<List<LinkedCompanion>> = companions

    override fun observeShortlist(companionUserId: String): Flow<Shortlist> = shortlists.map { it[companionUserId] ?: Shortlist(items = emptyList(), companionPoolPublishedAtEpochMs = null) }

    override fun observePoolSettings(): Flow<PoolSettings> = settings

    override suspend fun createInvite(): Result<String> = createInviteResult

    override suspend fun acceptInvite(code: String): Result<Unit> {
        acceptedCodes += code
        return acceptInviteResult
    }

    override suspend fun confirmCompanion(companionUserId: String): Result<Unit> {
        confirmed += companionUserId
        companions.value = companions.value.map {
            if (it.userId == companionUserId) it.copy(state = CompanionState.ACTIVE) else it
        }
        return Result.success(Unit)
    }

    override suspend fun setLocalName(companionUserId: String, localName: String?) {
        renamed += companionUserId to localName
        companions.value = companions.value.map {
            if (it.userId == companionUserId) it.copy(localName = localName) else it
        }
    }

    override suspend fun unlink(companionUserId: String) {
        unlinked += companionUserId
        companions.value = companions.value.map {
            if (it.userId == companionUserId) it.copy(state = CompanionState.REVOKED) else it
        }
    }

    override suspend fun setPoolSource(source: PoolSource) {
        poolSourcesSet += source
        settings.value = settings.value.copy(source = source)
    }

    override suspend fun setIncludeSeenByDefault(include: Boolean) {
        includeSeenSet += include
        settings.value = settings.value.copy(includeSeenByDefault = include)
    }

    override suspend fun refreshPublishedPool() {
        refreshPublishedPoolCalls++
    }
}
