package com.codingpit.muviss.core.remoteconfig

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.erase.DataEraseFence
import com.codingpit.muviss.core.common.remoteconfig.FeatureRequestProvider
import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import com.configcat.ConfigCache
import com.configcat.ConfigCatClient
import com.configcat.DataGovernance
import com.configcat.getValue
import com.configcat.log.LogLevel
import com.configcat.manualPoll
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * [RemoteConfig] served by ConfigCat (ADR 0026).
 *
 * **Manual poll.** The SDK never fetches on its own: [refreshIfStale] does, at
 * startup and on foreground, at most once per [STALE_AFTER_MS]. That keeps a
 * fetch off every screen and makes a background process (a WorkManager job, an
 * iOS BGTask) fetch nothing — those read whatever was stored at the last
 * foreground.
 *
 * **No user.** No user object is ever passed, so evaluation is local and
 * ConfigCat learns only that a device downloaded the config (and its IP, which
 * `docs/PRIVACY.md` says). EU data governance is on for the same reason.
 *
 * Every value falls back to [defaults]: an unreachable CDN, an empty cache and a
 * key that was deleted remotely all read as "the compiled default".
 */
internal class ConfigCatRemoteConfig(
    sdkKey: String,
    engine: HttpClientEngine,
    cache: ConfigCache,
    private val defaults: RemoteValues,
    private val clock: AppClock,
    private val erasures: DataEraseFence,
) : RemoteConfig {

    // Declared before the client: the SDK is handed this wrapper, not `cache`, so its write is fenced (#1061).
    private val fencedCache = FencedConfigCache(cache, erasures)

    private val client: ConfigCatClient = ConfigCatClient(sdkKey) {
        pollingMode = manualPoll()
        configCache = fencedCache
        httpEngine = engine
        dataGovernance = DataGovernance.EU_ONLY
        // The SDK logs the evaluated value at INFO; a served credential must not reach a log.
        logLevel = LogLevel.ERROR
    }

    private val state = MutableStateFlow(defaults)
    override val values: StateFlow<RemoteValues> = state

    private val refreshing = Mutex()
    private var lastFetchEpochMs: Long? = null

    override suspend fun awaitStored(): RemoteValues {
        // Ready means the persisted copy has been read (or there is none) — never a download.
        runCatchingNonCancellation { client.waitForReady() }
        return evaluate()
    }

    override suspend fun refreshIfStale() {
        refreshing.withLock {
            val last = lastFetchEpochMs
            val now = clock.nowEpochMs()
            if (last != null && now - last in 0 until STALE_AFTER_MS) return
            // Taken before the SDK asks TMDB's CDN, and it is what the SDK's write below is checked against.
            fencedCache.requestedAt = erasures.request()
            runCatchingNonCancellation { client.forceRefresh() }
            // A failed fetch still counts: retrying on every foreground would hammer the CDN offline.
            lastFetchEpochMs = now
            evaluate()
        }
    }

    private suspend fun evaluate(): RemoteValues {
        val next = runCatchingNonCancellation {
            RemoteValues(
                byokEnabled = client.getValue(KEY_BYOK_ENABLED, defaults.byokEnabled),
                tmdbFallbackReadToken = tmdbReadTokenOrNull(client.getValue(KEY_TMDB_FALLBACK_TOKEN, "")),
                featureRequestProvider = FeatureRequestProvider.fromStored(
                    client.getValue(KEY_FEATURE_REQUEST_PROVIDER, defaults.featureRequestProvider.key),
                ),
                featureRequestUrl = client.getValue(KEY_FEATURE_REQUEST_URL, defaults.featureRequestUrl.orEmpty())
                    .trim()
                    .takeIf { it.isNotEmpty() },
                bugReportUrl = client.getValue(KEY_BUG_REPORT_URL, defaults.bugReportUrl.orEmpty())
                    .trim()
                    .takeIf { it.isNotEmpty() },
                nextPickDefaultResultMode = client.getValue(KEY_NEXT_PICK_DEFAULT_RESULT_MODE, defaults.nextPickDefaultResultMode)
                    .trim()
                    .ifEmpty { defaults.nextPickDefaultResultMode },
            )
        } ?: defaults
        state.value = next
        return next
    }

    private inline fun <T> runCatchingNonCancellation(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * The cache the SDK writes its fetched config through. The SDK does that write itself, inside the awaited
     * `forceRefresh` (its `fetchConfig` calls `writeCache` before the answer is returned), so nothing on our side
     * is around it to take the fence: this wrapper does. [requestedAt] is set by [refreshIfStale] under its lock,
     * before `forceRefresh`, and starts at the count at construction, so a write can never be unfenced.
     *
     * Known gap, tracked in #1080 and not closed here: the SDK runs each fetch in its own coroutine. If
     * [refreshIfStale] is cancelled mid-fetch, that write still lands later, checked against whatever count is
     * armed then.
     */
    private class FencedConfigCache(
        private val delegate: ConfigCache,
        private val erasures: DataEraseFence,
    ) : ConfigCache {
        @Volatile
        var requestedAt: Long = erasures.request()

        override suspend fun read(key: String): String? = delegate.read(key)

        override suspend fun write(key: String, value: String) {
            erasures.write(requestedAt) { delegate.write(key, value) }
        }
    }

    companion object {
        /**
         * The served value only replaces the built-in credential when it has the shape of a
         * TMDB v4 read token (a JWT: `eyJ…` and three dot-separated base64url parts). ConfigCat
         * cannot store an empty text setting, so "use the built-in one" is any placeholder —
         * `none`, `-`, or the `""` its editor saves when you type two quotes — and a placeholder
         * sent as a Bearer token would fail every TMDB request as Unauthorized.
         */
        internal fun tmdbReadTokenOrNull(served: String): String? = served.trim().takeIf { TMDB_READ_TOKEN.matches(it) }

        private val TMDB_READ_TOKEN = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

        const val KEY_BYOK_ENABLED = "byokEnabled"
        const val KEY_TMDB_FALLBACK_TOKEN = "tmdbFallbackReadToken"
        const val KEY_FEATURE_REQUEST_PROVIDER = "featureRequestProvider"
        const val KEY_FEATURE_REQUEST_URL = "featureRequestUrl"
        const val KEY_BUG_REPORT_URL = "bugReportUrl"
        const val KEY_NEXT_PICK_DEFAULT_RESULT_MODE = "nextPickDefaultResultMode"

        /** One fetch per hour per active device is the budget ADR 0026 sets against the free tier. */
        const val STALE_AFTER_MS: Long = 60L * 60L * 1000L
    }
}
