package com.codingpit.muviss.core.sync

/**
 * Most rows [SyncEngine] hands [SyncBackend.push] for one table in one call.
 *
 * Bounded because a request body is not: a first sync of a large library used
 * to be one POST per table holding every row, so a single 5xx or timeout threw
 * away all of it, and a request body large enough is refused outright by a
 * proxy in front of the API. Chunks also let the engine clear the dirty flag
 * per chunk, so a failure at chunk seven leaves chunks one to six sent.
 */
const val PUSH_CHUNK_ROWS: Int = 500

/** Rows [SyncBackend] implementations ask for per page when pulling. Below any plausible server-side row cap, so a page is not routinely truncated. */
const val PULL_PAGE_ROWS: Int = 500

/**
 * Most ids bound into one `IN (...)` list. SQLite's default limit on bound
 * variables is 999 on the versions Android's minSdk ships, and `IN :ids` binds
 * one per element, so anything that takes a set of media ids is chunked to this.
 */
internal const val SQL_ID_CHUNK: Int = 400
