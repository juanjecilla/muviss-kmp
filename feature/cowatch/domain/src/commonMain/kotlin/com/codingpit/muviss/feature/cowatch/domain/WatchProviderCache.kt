package com.codingpit.muviss.feature.cowatch.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.concurrency.REFRESH_CONCURRENCY
import com.codingpit.muviss.core.common.concurrency.mapBounded
import com.codingpit.muviss.models.MediaId

/**
 * Local cache of a title's flatrate watch-provider ids (EPIC 41 follow-up,
 * #122).
 *
 * Cache, not user data — the same distinction ADR 0015 draws for the episode
 * catalog: nobody here authored it, TMDB/JustWatch is the only source of
 * truth, and a stale entry is replaced wholesale rather than reconciled.
 * [SqlDelightWatchProviderCache][com.codingpit.muviss.feature.cowatch.data.SqlDelightWatchProviderCache]
 * is the implementation, over `titleWatchProviders`.
 */
interface WatchProviderCache {
    /** Whatever is cached for [mediaIds], keyed by id. A missing key means "never fetched". */
    suspend fun get(mediaIds: Set<MediaId>): Map<MediaId, CachedProviders>

    suspend fun put(mediaId: MediaId, region: String, flatrateProviderIds: Set<String>)
}

/** One title's cached flatrate provider ids, and when/where they were fetched. */
data class CachedProviders(
    val region: String,
    val flatrateProviderIds: Set<String>,
    val fetchedAtEpochMs: Long,
)

/**
 * Fetches a title's flatrate provider ids for the device's current region
 * (EPIC 41 follow-up, #122).
 *
 * Implemented in `feature/cowatch/data` over `MetadataProvider.watchProviders`
 * (`:core:network`) — this interface exists so [WatchProviderRefresher] stays
 * in `:domain` without reaching for a vendor (ADR 0004). [region] is read
 * fresh on every access, mirroring `MetadataLocale`'s own contract, so a
 * Settings change is picked up without restarting anything.
 */
interface WatchProviderSource {
    val region: String

    suspend fun fetchFlatrateIds(mediaId: MediaId): Result<Set<String>>
}

/**
 * Keeps [WatchProviderCache] current for a bounded set of titles (EPIC 41
 * follow-up, #122) — the thing that makes the Shortlist's ranking possible
 * without an N-title fan-out on every open, which is exactly what #122
 * rejected as a design.
 *
 * Two things bound the cost instead of a screen re-fetching everything every
 * time it opens:
 *
 * - **A TTL.** An entry younger than [maxAgeMs] is served as-is; TMDB is
 *   rate-limited and a service's flatrate catalog does not change hour to
 *   hour, so refetching costs nothing that matters if it happens once a day.
 * - **Region-aware invalidation.** An entry whose stored region no longer
 *   matches [WatchProviderSource.region] is treated as stale regardless of
 *   age, so a Settings region change (EPIC 8) is not stuck behind the TTL —
 *   [CachedProviders.region] exists for exactly this check.
 *
 * A fetch failure falls back to whatever is cached (even if stale) rather
 * than surfacing an error: this only ever feeds a ranking signal, and one
 * title's outage should not blank out its cached answer, following the same
 * best-effort-per-title posture as `RefreshCollectionSnapshotsUseCase`.
 */
class WatchProviderRefresher(
    private val cache: WatchProviderCache,
    private val source: WatchProviderSource,
    private val clock: AppClock,
    private val concurrency: Int = REFRESH_CONCURRENCY,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    /** Ensures the cache is current for [mediaIds], then returns each one's flatrate ids (fresh or, on a failed fetch, stale). */
    suspend fun refresh(mediaIds: Set<MediaId>): Map<MediaId, Set<String>> {
        if (mediaIds.isEmpty()) return emptyMap()
        val region = source.region
        val now = clock.nowEpochMs()
        val cached = cache.get(mediaIds)
        return mediaIds.toList().mapBounded(concurrency) { mediaId ->
            val existing = cached[mediaId]
            val fresh = existing != null && existing.region == region && now - existing.fetchedAtEpochMs < maxAgeMs
            val ids = if (fresh) {
                existing.flatrateProviderIds
            } else {
                source.fetchFlatrateIds(mediaId).getOrNull()
                    ?.also { cache.put(mediaId, region, it) }
                    ?: existing?.flatrateProviderIds.orEmpty()
            }
            mediaId to ids
        }.toMap()
    }

    private companion object {
        const val DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
