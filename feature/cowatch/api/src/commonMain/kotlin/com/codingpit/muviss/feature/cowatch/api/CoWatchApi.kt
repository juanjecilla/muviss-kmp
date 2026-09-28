package com.codingpit.muviss.feature.cowatch.api

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.Flow

/**
 * Co-watch's public contract (EPIC 41, ADR 0022) — the only thing peers may
 * depend on (ADR 0004).
 *
 * Everything here is one-directional by design. This account publishes a Watch
 * Pool and reads what a Companion published; nothing a Companion does reaches
 * this account's `WatchProgress`, `CollectionEntry` or anything else it owns.
 * There is deliberately no "mark it watched for both of us" on this interface,
 * and adding one is a change to ADR 0022, not to this file (see #123).
 */
interface CoWatchApi {

    /** The Companions this account has linked with, in the order they were linked. */
    fun observeCompanions(): Flow<List<LinkedCompanion>>

    /**
     * What this account and [companionUserId] could watch together, recomputed
     * from both Watch Pools. Derived, never stored — like WatchNext, and for
     * the same reason.
     */
    fun observeShortlist(companionUserId: String): Flow<Shortlist>

    /** This account's own pool settings: where the pool comes from, and whether seen titles are in it by default. */
    fun observePoolSettings(): Flow<PoolSettings>

    /**
     * Mints an invite code for this account to send out of band.
     *
     * The code carries this account's user id plus a fresh nonce. RLS cannot
     * express "readable if you know the secret" without a `security definer`
     * RPC this project has never had, so the code carries what a lookup would
     * otherwise have returned (ADR 0022).
     */
    suspend fun createInvite(): Result<String>

    /** Answers someone else's invite code. Both sides must accept before a link is usable. */
    suspend fun acceptInvite(code: String): Result<Unit>

    /** Confirms an acceptance that came back for a code this account issued. */
    suspend fun confirmCompanion(companionUserId: String): Result<Unit>

    /** Names a Companion, on this device only. Null clears it back to the default label. */
    suspend fun setLocalName(companionUserId: String, localName: String?)

    /**
     * Ends the link from this side alone, and tombstones everything this
     * account published to them so the server copy goes away on the next push.
     *
     * It cannot reach their device: what they already pulled stays until their
     * app next syncs. The UI says so rather than implying otherwise.
     */
    suspend fun unlink(companionUserId: String)

    suspend fun setPoolSource(source: PoolSource)

    suspend fun setIncludeSeenByDefault(include: Boolean)

    /** Recomputes this account's pool and queues the difference for the next push. */
    suspend fun refreshPublishedPool()
}

/**
 * Another account this one has linked with. [localName] never leaves this device.
 *
 * Named `LinkedCompanion` rather than `Companion` — the domain word — because
 * `Companion` is Kotlin's companion-object identifier, and a type with that name
 * is shadowed inside any class that declares one. The glossary term is still
 * Companion (CONTEXT.md); this is a language collision, not a rename of the
 * concept.
 */
data class LinkedCompanion(
    val userId: String,
    val localName: String?,
    val state: CompanionState,
    /** When their pool was last published, or null if none has ever arrived. */
    val poolPublishedAtEpochMs: Long? = null,
)

/**
 * How far a link has got. A link is two independent statements, one per side,
 * so these are combinations of the two rather than a single shared status.
 */
enum class CompanionState {
    /** We invited them; nothing has come back. */
    INVITED,

    /** They answered, or invited us, and we have not confirmed yet. */
    AWAITING_CONFIRMATION,

    /** Both sides accepted. The only state in which pools flow. */
    ACTIVE,

    /** One side ended it. */
    REVOKED,
}

/** Where this account's Watch Pool comes from. */
sealed interface PoolSource {
    /**
     * The default: entries this account has not started. "I mean to watch this"
     * is what NotStarted already means here (CONTEXT.md), so the feature works
     * without anyone curating anything first.
     */
    data object NotStarted : PoolSource

    /** A named list instead, for someone who does not want their whole watchlist in play. */
    data class Named(val listId: String) : PoolSource
}

data class PoolSettings(
    val source: PoolSource,
    /**
     * What a title with no Revisit Willingness answer falls back to. An explicit
     * per-title answer always wins over this.
     */
    val includeSeenByDefault: Boolean,
)

/**
 * The ranked answer, plus how old the other half of it is.
 *
 * [companionPoolPublishedAtEpochMs] is surfaced rather than hidden because the
 * staleness is structural: only a Companion's own device can publish their
 * pool, so no amount of syncing here can freshen it (ADR 0022, #121).
 */
data class Shortlist(
    val items: List<ShortlistItem>,
    val companionPoolPublishedAtEpochMs: Long?,
)

/**
 * One title both people could watch, and why it ranks where it does.
 *
 * [reasons] exists so the UI can explain a position in one line. A ranking
 * nobody can explain is one nobody trusts, and this app has no analytics to
 * tune an opaque score with.
 */
data class ShortlistItem(
    val mediaId: MediaId,
    val mediaType: MediaType,
    val title: String,
    val posterUrl: String?,
    val runtimeMinutes: Int?,
    val reasons: Set<ShortlistReason>,
)

enum class ShortlistReason {
    /** Both people pinned it. The strongest signal there is: two deliberate acts. */
    BOTH_PINNED,

    /** Neither has started it — the cleanest thing to begin together. */
    NEITHER_STARTED,

    /** One of them has seen it and said they would watch it again. */
    REVISIT,

    /**
     * Both pools name a flatrate provider in common (#122, EPIC 41
     * follow-up). A tie-break within a tier, not a tier of its own — see
     * `ShortlistRanking`'s KDoc.
     */
    SHARED_AVAILABILITY,
}
