package com.codingpit.muviss.core.sync.supabase

/**
 * Supplies a valid Supabase access token, refreshing it when it is about to
 * expire and retrying once on a 401.
 *
 * Extracted so co-watch (EPIC 41) can reach the same tokens without depending
 * on `SupabaseSyncBackend` itself. Getting this right is fiddly — proactive
 * refresh at a leeway, a reactive retry, a mutex so two concurrent callers do
 * not both spend the refresh token, which GoTrue rotates — and a second copy
 * of it would be a second thing to get wrong.
 */
internal interface SupabaseTokenSource {
    suspend fun <T> withAccessToken(block: suspend (String) -> T): T
}
