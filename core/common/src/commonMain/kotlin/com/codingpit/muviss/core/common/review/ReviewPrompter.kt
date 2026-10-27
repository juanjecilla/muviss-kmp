package com.codingpit.muviss.core.common.review

/**
 * Hands the platform's store review sheet to the OS (#410). Android's Play In-App Review and
 * iOS's `SKStoreReviewController` are the two real ones; desktop and web bind [None], which never
 * asks, so the ledger is never written there either.
 */
fun interface ReviewPrompter {
    /**
     * True when the request was handed to the OS. The OS may still show nothing (both stores ration
     * the sheet and do not say when they did), so this means "asked", not "shown". False means there
     * was nowhere to ask (no Activity or scene attached) and nothing should be recorded.
     */
    suspend fun prompt(): Boolean

    companion object {
        val None: ReviewPrompter = ReviewPrompter { false }
    }
}
