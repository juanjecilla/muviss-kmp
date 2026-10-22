package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.common.secrets.SecretStore
import com.codingpit.muviss.models.MetadataError
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whose credential TMDB requests are made under: what the Settings row shows. */
enum class TmdbKeySource {
    /** The one Muviss ships (or the one Remote Config serves in its place). */
    MUVISS,

    /** One the user supplied. */
    YOURS,
}

/** What came of [OwnTmdbKey.save]. Only [Saved] changes anything. */
sealed interface SaveOwnKeyResult {
    data object Saved : SaveOwnKeyResult

    /** Neither a v4 read token nor a v3 key by shape; nothing was sent to TMDB. */
    data object Malformed : SaveOwnKeyResult

    /** TMDB answered 401: the key is wrong or revoked. Not saved. */
    data object Rejected : SaveOwnKeyResult

    /** Offline, throttled or TMDB failing: the key could not be checked, so it was not saved. */
    data object CouldNotCheck : SaveOwnKeyResult
}

/**
 * The user's Own TMDB Key (EPIC 46, ADR 0027): stored in the [SecretStore],
 * checked against TMDB before it is kept, and first in the credential chain
 * whenever it exists.
 *
 * It is read from the store once and held, because [TmdbProvider] asks for the
 * credential on every request. [save] and [clear] write through, so a change
 * applies to the next request without a restart.
 *
 * Whether the app *offers* to take a key is `FeatureFlags.byokEnabled`; nothing
 * here looks at it, because turning the offer off must never strand a key
 * already in use.
 */
class OwnTmdbKey internal constructor(
    private val store: SecretStore,
    private val validator: TmdbKeyValidator,
) {
    constructor(store: SecretStore, client: HttpClient) : this(store, HttpTmdbKeyValidator(client))

    private val lock = Mutex()
    private val current = MutableStateFlow<Loaded?>(null)

    /** [TmdbKeySource] now, and again whenever it changes. */
    fun observeSource(): Flow<TmdbKeySource> = flow {
        credentials() // the store is read once, here, before the first emission
        emitAll(current.filterNotNull().map { it.source }.distinctUntilChanged())
    }

    /** The user's credential, or null if they have none. */
    internal suspend fun credentials(): TmdbCredentials? {
        current.value?.let { return it.credentials }
        return lock.withLock {
            current.value?.credentials ?: load().also { current.value = it }.credentials
        }
    }

    private suspend fun load(): Loaded {
        // A stored value that no longer fits either shape is treated as absent rather than sent, and so
        // is one the platform store cannot give back (a Keystore key lost to a restore, say): the
        // alternative is an exception reaching a screen, and "never exception text" is the rule here.
        val credentials = try {
            store.get(STORE_NAME)?.let(TmdbKeyShape::detect)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return Loaded(credentials)
    }

    /**
     * Checks [raw] with TMDB and, only if TMDB accepts it, keeps it. A failure
     * of any kind leaves the previous key (or none) exactly as it was.
     */
    suspend fun save(raw: String): SaveOwnKeyResult {
        val candidate = TmdbKeyShape.detect(raw) ?: return SaveOwnKeyResult.Malformed
        return when (validator.validate(candidate)) {
            KeyCheck.Accepted -> {
                lock.withLock {
                    store.put(STORE_NAME, raw.trim())
                    current.value = Loaded(candidate)
                }
                SaveOwnKeyResult.Saved
            }

            KeyCheck.Rejected -> SaveOwnKeyResult.Rejected

            KeyCheck.Unknown -> SaveOwnKeyResult.CouldNotCheck
        }
    }

    /** Back to Muviss's credential. */
    suspend fun clear() {
        lock.withLock {
            store.clear(STORE_NAME)
            current.value = Loaded(null)
        }
    }

    private class Loaded(val credentials: TmdbCredentials?) {
        val source: TmdbKeySource get() = if (credentials == null) TmdbKeySource.MUVISS else TmdbKeySource.YOURS
    }

    companion object {
        /** The name in the [SecretStore]. */
        const val STORE_NAME = "tmdb.ownKey"
    }
}

internal enum class KeyCheck { Accepted, Rejected, Unknown }

/** Asks TMDB whether a credential is good, with one cheap authenticated call. */
internal fun interface TmdbKeyValidator {
    suspend fun validate(credentials: TmdbCredentials): KeyCheck
}

/**
 * `GET /3/authentication` answers 200 for any valid v3 key or v4 token and 401
 * otherwise. Goes through the same policy and [tmdbCall] as every other TMDB
 * request, so a failure is a fixed-text [MetadataError] with no URL in it.
 */
internal class HttpTmdbKeyValidator(client: HttpClient) : TmdbKeyValidator {
    private val http = client.withTmdbPolicy(TmdbRetryPolicy(), cached = false)

    override suspend fun validate(credentials: TmdbCredentials): KeyCheck = try {
        tmdbCall { http.get("${TmdbProvider.BASE}/authentication") { authorize(credentials) } }
        KeyCheck.Accepted
    } catch (_: MetadataError.Unauthorized) {
        KeyCheck.Rejected
    } catch (_: MetadataError) {
        KeyCheck.Unknown
    }
}
