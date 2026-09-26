package com.codingpit.muviss.core.sync.companion

import com.codingpit.muviss.core.sync.PULL_PAGE_ROWS
import com.codingpit.muviss.core.sync.PUSH_CHUNK_ROWS
import com.codingpit.muviss.core.sync.SyncCursor
import com.codingpit.muviss.core.sync.supabase.SupabasePostgrestClient
import com.codingpit.muviss.core.sync.supabase.SupabaseTokenSource
import kotlinx.serialization.KSerializer

/**
 * [CompanionBackend] over the same PostgREST endpoints, tokens and `server_seq`
 * paging the main backend uses (ADR 0020) — only the tables differ.
 *
 * The one behaviour worth reading twice is that a pull here returns rows in
 * **both directions**. The policy grants a read to the author and to the
 * addressee, so this account sees its own outbound copies alongside what a
 * Companion addressed to it. Filtering that server-side would mean a second
 * query shape per table for no benefit: the rows are the same ones this device
 * just pushed, they are small, and sorting them out is a comparison against
 * `user_id`, which these change types carry precisely so the applier can do it.
 */
internal class SupabaseCompanionBackend(
    private val postgrest: SupabasePostgrestClient,
    private val tokens: SupabaseTokenSource,
) : CompanionBackend {

    override suspend fun push(changes: CompanionChangeSet): Result<Unit> = runCatching {
        // Links before pool rows: a pool row addressed to someone this account
        // has not yet stated a link with is meaningless, and on a first sync the
        // two go out together.
        pushRows(CompanionTable.LINK.wireName, changes.links, CompanionLinkChange.serializer())
        pushRows(CompanionTable.POOL_ENTRY.wireName, changes.poolEntries, CompanionPoolEntryChange.serializer())
    }

    override suspend fun pull(
        after: Map<CompanionTable, SyncCursor>,
        onPage: suspend (CompanionPage) -> Unit,
    ): Result<Unit> = runCatching {
        drainTable(CompanionTable.LINK, after, CompanionLinkChange.serializer(), { CompanionChangeSet(links = it) }, onPage)
        drainTable(CompanionTable.POOL_ENTRY, after, CompanionPoolEntryChange.serializer(), { CompanionChangeSet(poolEntries = it) }, onPage)
    }

    private suspend fun <T> pushRows(table: String, rows: List<T>, serializer: KSerializer<T>) {
        if (rows.isEmpty()) return
        rows.chunked(PUSH_CHUNK_ROWS).forEach { chunk ->
            tokens.withAccessToken { token -> postgrest.upsert(table, token, chunk, serializer) }
        }
    }

    /**
     * Pages [table] until a response comes back **empty**. A short page proves
     * nothing: a server-side `max_rows` truncates silently, so treating fewer
     * than [PULL_PAGE_ROWS] as "drained" would stop early and lose the rest
     * for good (ADR 0020).
     *
     * Ascending order is verified rather than trusted, for the same reason the
     * main backend verifies it: a server that ignored `order` would return an
     * arbitrary subset, and taking its highest `server_seq` as the cursor would
     * skip everything below it permanently.
     */
    private suspend fun <T> drainTable(
        table: CompanionTable,
        after: Map<CompanionTable, SyncCursor>,
        serializer: KSerializer<T>,
        wrap: (List<T>) -> CompanionChangeSet,
        onPage: suspend (CompanionPage) -> Unit,
    ) {
        var position = after[table]?.position ?: 0L
        while (true) {
            val rows = tokens.withAccessToken { token ->
                postgrest.selectPage(table.wireName, token, position, PULL_PAGE_ROWS, serializer)
            }
            if (rows.isEmpty()) return
            val ascending = rows.zipWithNext().all { (previous, next) -> previous.serverSeq < next.serverSeq }
            check(ascending && rows.first().serverSeq > position) {
                "${table.wireName} was not returned in ascending server_seq order past $position"
            }
            position = rows.last().serverSeq
            onPage(CompanionPage(table, wrap(rows.map { it.value }), SyncCursor(position)))
        }
    }
}
