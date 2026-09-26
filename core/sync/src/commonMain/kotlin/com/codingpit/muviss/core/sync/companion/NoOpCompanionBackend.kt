package com.codingpit.muviss.core.sync.companion

import com.codingpit.muviss.core.sync.SyncCursor

/**
 * What gets bound when sync is not configured in this build (ADR 0018) — the
 * co-watch twin of `NoOpSyncBackend`.
 *
 * Succeeds silently rather than failing: nothing upstream should have to ask
 * whether a backend exists, and a build without Supabase keys renders no
 * co-watch UI at all, so nothing calls this in practice.
 */
internal class NoOpCompanionBackend : CompanionBackend {
    override suspend fun push(changes: CompanionChangeSet): Result<Unit> = Result.success(Unit)

    override suspend fun pull(
        after: Map<CompanionTable, SyncCursor>,
        onPage: suspend (CompanionPage) -> Unit,
    ): Result<Unit> = Result.success(Unit)
}
