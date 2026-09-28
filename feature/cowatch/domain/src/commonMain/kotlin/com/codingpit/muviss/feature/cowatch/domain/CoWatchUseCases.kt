package com.codingpit.muviss.feature.cowatch.domain

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.ListsApi
import com.codingpit.muviss.feature.cowatch.api.CompanionState
import com.codingpit.muviss.feature.cowatch.api.LinkedCompanion
import com.codingpit.muviss.feature.cowatch.api.PoolSettings
import com.codingpit.muviss.feature.cowatch.api.PoolSource
import com.codingpit.muviss.feature.cowatch.api.Shortlist
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Builds this account's Watch Pool from the library and queues it for the next
 * push (EPIC 41).
 *
 * [collectionApi] is a provider rather than an instance, following
 * `WatchNextUseCase`: collection's repository already reaches for `ProgressApi`
 * to derive status, and taking peers eagerly is how this graph closes a
 * construction cycle that Koin follows until the stack overflows — on the first
 * frame, before anything renders. `AppGraphTest` is the only test that catches
 * it, and `Module.verify()` is not.
 */
class PublishWatchPoolUseCase(
    private val collectionApi: () -> CollectionApi,
    private val listsApi: () -> ListsApi,
    private val repository: CoWatchRepository,
    private val providerRefresher: WatchProviderRefresher,
) {
    suspend operator fun invoke() {
        val settings = repository.observePoolSettings().first()
        val summaries = collectionApi().observeSummaries().first()
        val namedContents = when (val source = settings.source) {
            is PoolSource.NotStarted -> emptySet()
            is PoolSource.Named -> listsApi().observeListContents(source.listId).first().map { it.mediaId }.toSet()
        }
        // Built once to know which titles are actually about to be published —
        // the refresh below only touches those, not the whole library — and
        // rebuilt with the (possibly just-refreshed) provider ids once known.
        // `WatchPoolBuilder` stays pure throughout; this use case is the only
        // place that talks to the cache (#122).
        val candidates = WatchPoolBuilder.build(summaries, settings, namedContents)
        val providerIds = providerRefresher.refresh(candidates.map { it.mediaId }.toSet())
        repository.publishPool(WatchPoolBuilder.build(summaries, settings, namedContents, providerIds))
    }
}

/**
 * The Shortlist for one Companion: this account's pool joined against theirs and
 * ranked (see [ShortlistRanking]).
 *
 * Recomputed rather than stored, like WatchNext — and, like WatchNext, it is one
 * implementation that every surface renders rather than a calculation each
 * screen repeats. It is *not* WatchNext though: that one answers "which show am
 * I mid-way through" over WATCHING, and this answers "what should two people
 * start" over the sets WatchNext excludes.
 */
class ObserveShortlistUseCase(
    private val collectionApi: () -> CollectionApi,
    private val listsApi: () -> ListsApi,
    private val repository: CoWatchRepository,
    private val providerCache: WatchProviderCache,
) {
    operator fun invoke(companionUserId: String): Flow<Shortlist> = combine(
        collectionApi().observeSummaries(),
        repository.observePoolSettings(),
        repository.observeCompanionPool(companionUserId),
        repository.observeCompanions(),
    ) { summaries, settings, theirPool, companions ->
        val namedContents = namedListContents(settings)
        // A local cache read only, never a fetch (#122): the Shortlist must
        // stay something opening the screen never triggers a network call
        // for. `PublishWatchPoolUseCase` (run once per open via
        // `refreshPublishedPool`, TTL-gated) is what keeps this cache current.
        val eligible = WatchPoolBuilder.build(summaries, settings, namedContents)
        val providerIds = providerCache.get(eligible.map { it.mediaId }.toSet()).mapValues { it.value.flatrateProviderIds }
        val mine = WatchPoolBuilder.build(summaries, settings, namedContents, providerIds)
        Shortlist(
            items = ShortlistRanking.rank(mine, theirPool),
            companionPoolPublishedAtEpochMs = companions.firstOrNull { it.userId == companionUserId }?.poolPublishedAtEpochMs,
        )
    }

    private suspend fun namedListContents(settings: PoolSettings): Set<MediaId> = when (val source = settings.source) {
        is PoolSource.NotStarted -> emptySet()
        is PoolSource.Named -> listsApi().observeListContents(source.listId).first().map { it.mediaId }.toSet()
    }
}

/**
 * Turns a pasted or tapped invite code into this side's link statement.
 *
 * Refuses a code naming this account itself: pairing with yourself would create
 * a link whose two halves are one row, which the server's own
 * `cowatch_link_not_self` check also rejects — better to say so here than to
 * surface a constraint violation.
 */
class AcceptInviteUseCase(
    private val repository: CoWatchRepository,
) {
    suspend operator fun invoke(raw: String): Result<Unit> {
        val code = InviteCode.decode(raw).getOrElse { return Result.failure(it) }
        val me = repository.currentUserId()
            ?: return Result.failure(IllegalStateException("sign in before accepting an invite"))
        if (code.userId == me) return Result.failure(IllegalArgumentException("that is your own invite code"))
        return repository.acceptInvite(code)
    }
}

/**
 * Mints an invite and remembers its nonce.
 *
 * The nonce is recorded before the code is handed out, because it is what makes
 * an unsolicited row harmless: an acceptance is only ever surfaced when it
 * answers a code this account actually issued (ADR 0022).
 */
class CreateInviteUseCase(
    private val repository: CoWatchRepository,
    private val randomHex: (Int) -> String,
) {
    suspend operator fun invoke(): Result<String> {
        val me = repository.currentUserId()
            ?: return Result.failure(IllegalStateException("sign in before inviting someone"))
        val code = InviteCode(me, randomHex(InviteCode.NONCE_HEX_LENGTH))
        repository.recordIssuedInvite(code)
        return Result.success(code.encode())
    }
}

/** Companions this account can actually build a Shortlist with. */
class ObserveActiveCompanionsUseCase(
    private val repository: CoWatchRepository,
) {
    operator fun invoke(): Flow<List<LinkedCompanion>> = repository.observeCompanions()
        .map { companions -> companions.filter { it.state == CompanionState.ACTIVE } }
}

/** Every Companion, including the half-finished ones the Profile screen has to show. */
class ObserveCompanionsUseCase(
    private val repository: CoWatchRepository,
) {
    operator fun invoke(): Flow<List<LinkedCompanion>> = repository.observeCompanions()
}
