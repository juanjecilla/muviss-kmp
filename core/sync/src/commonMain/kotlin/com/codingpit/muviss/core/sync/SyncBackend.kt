package com.codingpit.muviss.core.sync

import kotlinx.coroutines.flow.Flow

/**
 * The extensibility seam for cloud sync backends (ADR 0009) — mirrors how
 * [com.codingpit.muviss.core.network.MetadataProvider] abstracts TMDB (ADR
 * 0001). `SupabaseSyncBackend` is the only implementation today; a Firebase
 * (or self-hosted) backend plugs in later by implementing this interface —
 * no change to [SyncEngine] or the UI. Shaped around the change-log
 * ([SyncChangeSet], i.e. dirty local rows / changed remote rows), not any
 * backend's native SDK shape, so [SyncEngine] never has to know which
 * backend it's talking to.
 *
 * Auth is anonymous-first ([signInAnonymously]) with an OAuth upgrade
 * ([beginOAuth] / [completeOAuth]) — no backend requires a password, matching
 * this app's "no login required" default (ADR 0002). The email one-time-code
 * flow this interface carried until ADR 0014 is gone: Supabase's free tier
 * refuses to customise the email templates, and its stock ones send a
 * clickable link rather than a typable code, so the flow could not be made to
 * work without paying for a plan or standing up an SMTP provider. See
 * docs/SYNC.md.
 */
interface SyncBackend {
    val id: SyncBackendId

    /**
     * The current session, or null when signed out. Replays its latest
     * value to new subscribers so callers (e.g. [SyncEngine], the profile
     * screen) can read the current state synchronously via `.first()`
     * without racing startup session restoration.
     */
    val session: Flow<SyncSession?>

    /** Starts (or resumes) an anonymous session — no email required, the default entry point. */
    suspend fun signInAnonymously(): Result<SyncSession>

    /**
     * Starts an OAuth sign-in and returns the URL to open in a browser.
     *
     * Two calls rather than one because the user leaves the app in between:
     * the provider's page runs in a browser, and the result comes back as a
     * redirect to [redirectUri] that the platform delivers separately (on
     * Android, an intent into `MainActivity`). Whoever receives that redirect
     * calls [completeOAuth] with its `code` parameter.
     *
     * The implementation holds the PKCE verifier for the attempt, so callers
     * never handle it. One attempt is tracked at a time — starting a second
     * discards the first, which matches what a user tapping the button twice
     * expects.
     */
    suspend fun beginOAuth(provider: OAuthProvider, redirectUri: String): Result<String>

    /** Exchanges the `code` from a [beginOAuth] redirect for a real session. Fails if no attempt is in flight. */
    suspend fun completeOAuth(authCode: String): Result<SyncSession>

    suspend fun signOut()

    /**
     * Pushes local [changes] to the backend. Implementations upsert per
     * row; server-side last-write-wins (on each row's own
     * `updated_at_epoch_ms`) protects against a stale push clobbering a
     * newer remote write that arrived from another device in between — see
     * docs/SYNC.md's schema for how Supabase enforces this without any
     * client-side coordination.
     *
     * A field that is null in a row **must reach the backend as null**: that is
     * how a user clearing a rating or a note propagates. An implementation
     * that omits nulls from its wire format leaves the old value in place on
     * the server, where it comes back to every other device.
     *
     * [SyncEngine] hands over at most [PUSH_CHUNK_ROWS] rows per table per
     * call, and an implementation may split further; nothing may assume a call
     * is atomic across tables.
     */
    suspend fun push(changes: SyncChangeSet): Result<Unit>

    /**
     * Streams every remote row changed after [after], one page at a time, in
     * the order pages arrive: tables parent-first, and within a table in the
     * backend's own change order.
     *
     * [after] holds, per table, the [SyncCursor] of the last page this device
     * fully applied; a table with no entry is pulled from the beginning. The
     * cursors are **opaque**: the engine stores and returns them and never
     * interprets one, so a backend is free to use a sequence number, a commit
     * timestamp or a token. What it must guarantee is that a cursor only moves
     * forward over rows already delivered, and that a client which stops
     * anywhere and resumes from the last cursor it saw misses nothing.
     *
     * [onPage] is called once per non-empty page and must return before the
     * next is requested; an exception from it fails the pull. A backend has to
     * drain a table until it is *empty*, not until a page comes back short —
     * a server may cap a response below the page size it was asked for, and a
     * short page then means "capped", not "finished".
     */
    suspend fun pull(after: Map<SyncTable, SyncCursor>, onPage: suspend (SyncPage) -> Unit): Result<Unit>
}

/**
 * The tables [SyncEngine] replicates, in the order they are pushed and pulled:
 * parents before the rows that refer to them, so a device that is interrupted
 * midway is left with a title and no ticks rather than ticks for a title it
 * does not have.
 *
 * [cursorKey] is the local table name and what `syncCursor` is keyed by. It is
 * deliberately not a backend's remote table name — those are the backend's own
 * business.
 */
enum class SyncTable(val cursorKey: String) {
    COLLECTION_ENTRY("collectionEntry"),
    EPISODE_PROGRESS("episodeProgress"),
    MEDIA_LIST("mediaList"),
    LIST_ENTRY("listEntry"),
    TRIAGE_DECISION("triageDecision"),
    TRIAGE_SNOOZE("triageSnooze"),
    EPISODE_PLAY("episodePlay"),
}

/**
 * A backend's bookmark in one table's change feed. Opaque by contract — see
 * [SyncBackend.pull]. The engine persists [position] and hands it back; it
 * never compares two cursors or does arithmetic on one.
 */
data class SyncCursor(val position: Long)

/** One page of remote changes for a single [table]: [changes] has only that table's list populated. [cursor] is where the feed stands after this page. */
class SyncPage(val table: SyncTable, val changes: SyncChangeSet, val cursor: SyncCursor)
