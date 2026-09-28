package com.codingpit.muviss.core.sync

/**
 * Refreshes a bounded set of titles' provider snapshot (episode counts,
 * poster, production status) after a pull touched them — issue #101.
 *
 * The plan behind ADR 0020 had the engine queue this; it shipped without a
 * consumer because the Library already refetches every saved title on each
 * visit and pull-to-refresh, so until automatic sync (EPIC 40) nothing ever
 * saw the staleness. A pulled row carries whichever device wrote it last, and
 * that device's snapshot can be old — the invariant that matters (`seen <=
 * aired`) is protected at pull time by [RemoteApplier.reconcile] regardless,
 * so this is a freshness gap, not a correctness one, but it now matters:
 * automatic sync can run with no screen open to trigger the Library's own
 * refetch, and a widget renders whatever is in the database.
 *
 * `:core:sync` cannot call the collection feature's use case directly (ADR
 * 0004 — a core module does not depend on a feature), so this is the seam:
 * `:app:shared` binds the real implementation over `CollectionApi`, the same
 * indirection [com.codingpit.muviss.core.common.widget.WidgetRefresher] uses
 * for the same reason. Implementations must be best-effort and must not
 * throw — a stale snapshot is not a failed sync, exactly like a stale widget
 * is not.
 */
fun interface TitleRefresher {
    suspend fun refresh(mediaIds: Set<String>)
}

/** The default binding `:core:sync` ships on its own, and every test that does not care about this. */
object NoOpTitleRefresher : TitleRefresher {
    override suspend fun refresh(mediaIds: Set<String>) = Unit
}
