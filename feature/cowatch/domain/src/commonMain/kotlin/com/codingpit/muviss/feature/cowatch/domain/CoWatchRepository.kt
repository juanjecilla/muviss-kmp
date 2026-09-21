package com.codingpit.muviss.feature.cowatch.domain

import com.codingpit.muviss.feature.cowatch.api.LinkedCompanion
import com.codingpit.muviss.feature.cowatch.api.PoolSettings
import com.codingpit.muviss.feature.cowatch.api.PoolSource
import kotlinx.coroutines.flow.Flow

/**
 * Storage and transport for co-watch (EPIC 41), behind the usual `:domain`
 * interface so the use cases never meet SQLDelight or a vendor (ADR 0004).
 *
 * Note what is missing: there is no way to write anything belonging to a
 * Companion. Inbound data is cache this device replaces on a pull, and every
 * mutation here is about rows this account owns (ADR 0022's first two
 * invariants). If a method ever needs to change a Companion's data, the ADR is
 * what has to change first.
 */
interface CoWatchRepository {

    fun observeCompanions(): Flow<List<LinkedCompanion>>

    /** What [companionUserId] last published to this account. Cache; empty until a pull brings some. */
    fun observeCompanionPool(companionUserId: String): Flow<List<PoolItem>>

    fun observePoolSettings(): Flow<PoolSettings>

    /** This account's own id, or null when signed out. Needed because every co-watch row is addressed. */
    suspend fun currentUserId(): String?

    /** Records an invite this account issued, so a later acceptance can be matched against its nonce. */
    suspend fun recordIssuedInvite(code: InviteCode)

    /** Answers someone's invite by writing this side's link statement, addressed to them. */
    suspend fun acceptInvite(code: InviteCode): Result<Unit>

    /** Moves this side's statement to accepted, once an acceptance for a code we issued has arrived. */
    suspend fun confirmCompanion(companionUserId: String)

    suspend fun setLocalName(companionUserId: String, localName: String?)

    /**
     * Ends the link from this side and tombstones everything published to them.
     *
     * Both halves happen together: revoking the link while leaving the pool
     * rows behind would leave a readable copy on the server addressed to
     * someone this account no longer has a relationship with.
     */
    suspend fun unlink(companionUserId: String)

    suspend fun setPoolSource(source: PoolSource)

    suspend fun setIncludeSeenByDefault(include: Boolean)

    /**
     * Replaces what this account publishes to every active Companion with
     * [items], tombstoning whatever dropped out.
     *
     * A removal has to be a tombstone rather than a deletion: last-write-wins
     * has nothing to compare an absent row against, so a hard delete would be
     * undone by the next pull (ADR 0013's reasoning, unchanged here).
     */
    suspend fun publishPool(items: List<PoolItem>)
}
