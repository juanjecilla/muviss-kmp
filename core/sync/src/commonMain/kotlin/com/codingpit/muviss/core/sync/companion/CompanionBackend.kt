package com.codingpit.muviss.core.sync.companion

import com.codingpit.muviss.core.sync.SyncCursor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The co-watch half of the cloud seam (EPIC 41, ADR 0022), deliberately beside
 * [com.codingpit.muviss.core.sync.SyncBackend] rather than inside it.
 *
 * `SyncChangeSet` means *this account's own change-log*: six lists of rows this
 * user wrote, going out and coming back. `SyncChangeSetShapeTest` exists to keep
 * that true. What travels here is a different animal and the type system should
 * say so:
 *
 * - it is **addressed** — every row names the one account it is for, which is
 *   what the RLS policy compares against; and
 * - it is **read-only on the way in** — a pulled row belonging to a Companion is
 *   rendered, never applied to anything this user owns (ADR 0022's first
 *   invariant).
 *
 * Both halves share everything underneath: the same PostgREST client, the same
 * token refresh, the same `server_seq` paging from ADR 0020. Only the tables and
 * the cursor differ.
 *
 * A pull returns rows in **both directions** — the policy grants a read to the
 * author *and* the addressee, so this account sees its own outbound copies as
 * well as what was sent to it. That is why [userId] is explicit on these change
 * types and implicit on the other six: for a single-owner table the row is
 * necessarily yours, and here it is the thing that has to be checked.
 */
interface CompanionBackend {

    /**
     * Sends locally-changed link statements and pool rows.
     *
     * Every row must carry this account's own id in [CompanionLinkChange.userId]
     * / [CompanionPoolEntryChange.userId]. The server's `with check
     * (auth.uid() = user_id)` refuses anything else, which is what stops a
     * client forging a row as somebody else.
     */
    suspend fun push(changes: CompanionChangeSet): Result<Unit>

    /**
     * Pages both tables from [after], handing each page to [onPage] as it
     * arrives. Like the main backend, a table is drained only when a page comes
     * back **empty**: a server-side `max_rows` truncates silently, so a short
     * page proves nothing (ADR 0020).
     */
    suspend fun pull(after: Map<CompanionTable, SyncCursor>, onPage: suspend (CompanionPage) -> Unit): Result<Unit>
}

/** The two co-watch tables, paged independently, each with its own cursor. */
enum class CompanionTable(val wireName: String) {
    LINK("cowatch_link"),
    POOL_ENTRY("cowatch_pool_entry"),
}

/** One page of one [CompanionTable], and the cursor position it ends at. */
class CompanionPage(val table: CompanionTable, val changes: CompanionChangeSet, val cursor: SyncCursor)

/** Rows travelling in either direction. Empty lists are the common case. */
data class CompanionChangeSet(
    val links: List<CompanionLinkChange> = emptyList(),
    val poolEntries: List<CompanionPoolEntryChange> = emptyList(),
) {
    val isEmpty: Boolean get() = links.isEmpty() && poolEntries.isEmpty()
    val size: Int get() = links.size + poolEntries.size
}

/**
 * One side's statement about one link — mirrors `cowatch_link`.
 *
 * A link is two of these, one per account, because neither side can write the
 * other's row. "Linked" is therefore an agreement read off two independent
 * statements, which is how ADR 0022 keeps every row to exactly one writer.
 *
 * [nonce] is the random half of the invite code. An acceptance is only ever
 * surfaced when its nonce matches a code this account actually issued: the code
 * carries the inviter's user id in the clear (ADR 0022 explains why RLS leaves
 * no alternative), so the nonce is what stops an unsolicited row addressed at a
 * known id from presenting as an invitation.
 *
 * `localName` is absent on purpose — what one person calls another never leaves
 * their device.
 */
@Serializable
data class CompanionLinkChange(
    @SerialName("user_id") val userId: String,
    @SerialName("recipient_id") val recipientId: String,
    val state: String,
    val nonce: String,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
    @SerialName("updated_at_epoch_ms") val updatedAtEpochMs: Long,
    val deleted: Boolean,
)

/**
 * One title in a Watch Pool — mirrors `cowatch_pool_entry`.
 *
 * This is the whole of what co-watch ever sends about a person's library, and
 * the list is short on purpose (ADR 0022's third invariant). [started] and
 * [seen] are the two bits the ranking function needs; neither carries how far,
 * when, or how often, and no tick, play, rating or note is here at all.
 *
 * [flatrateProviderIds] (#122, EPIC 41 follow-up) is comma-joined like
 * [genres], not a JSON array: PostgREST's `explicitNulls` handling (ADR 0020)
 * only has to reason about scalar columns this way, matching every other
 * denormalized field on this row.
 */
@Serializable
data class CompanionPoolEntryChange(
    @SerialName("user_id") val userId: String,
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("media_id") val mediaId: String,
    @SerialName("media_type") val mediaType: String,
    val title: String,
    @SerialName("poster_url") val posterUrl: String?,
    val genres: String,
    @SerialName("runtime_minutes") val runtimeMinutes: Int?,
    val started: Boolean,
    val seen: Boolean,
    val pinned: Boolean,
    @SerialName("updated_at_epoch_ms") val updatedAtEpochMs: Long,
    val deleted: Boolean,
    @SerialName("flatrate_provider_ids") val flatrateProviderIds: String = "",
)
