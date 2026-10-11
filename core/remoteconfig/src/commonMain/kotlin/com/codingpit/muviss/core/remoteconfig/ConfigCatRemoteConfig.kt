package com.codingpit.muviss.core.remoteconfig

import com.codingpit.muviss.core.common.AppClock
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

/**
 * [RemoteConfig] served by ConfigCat (ADR 0026).
 *
 * **Manual poll.** The SDK never fetches on its own: [refreshIfStale] does, at
 * startup and on foreground, at most once per [staleAfterMs]. That keeps a
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
    private val staleAfterMs: Long = STALE_AFTER_MS,
) : RemoteConfig {

    private val client: ConfigCatClient = ConfigCatClient(sdkKey) {
        pollingMode = manualPoll()
        configCache = cache
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
            if (last != null && now - last in 0 until staleAfterMs) return
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
                tmdbFallbackReadToken = client.getValue(KEY_TMDB_FALLBACK_TOKEN, "").trim().ifEmpty { null },
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

    companion object {
        const val KEY_BYOK_ENABLED = "byokEnabled"
        const val KEY_TMDB_FALLBACK_TOKEN = "tmdbFallbackReadToken"

        /** One fetch per hour per active device is the budget ADR 0026 sets against the free tier. */
        const val STALE_AFTER_MS: Long = 60L * 60L * 1000L
    }
}
