package com.codingpit.muviss.core.common.review

/**
 * The per-device counters behind [ReviewPromptPolicy] (#410). Backed by `appSettings` columns
 * (`21.sqm`) in `:feature:settings:data`; a seam here so progress can count ticks without
 * depending on settings.
 */
interface ReviewLedger {
    suspend fun read(): ReviewRecord

    /** Stamps the install date, but only the first time on this device: later calls change nothing. */
    suspend fun stampInstalledIfUnknown(nowEpochMs: Long)

    /** Counts one hand tick. Atomic in the store, so two ticks at once both count. */
    suspend fun countTick()

    suspend fun recordAsk(nowEpochMs: Long, version: String)

    companion object {
        /** Counts nothing and reads as an install that was never stamped, so it never asks. For tests and graphs with no store. */
        val None: ReviewLedger = object : ReviewLedger {
            override suspend fun read() = ReviewRecord(installedAtEpochMs = null, tickCount = 0, lastAskedAtEpochMs = null, lastAskedVersion = null)
            override suspend fun stampInstalledIfUnknown(nowEpochMs: Long) = Unit
            override suspend fun countTick() = Unit
            override suspend fun recordAsk(nowEpochMs: Long, version: String) = Unit
        }
    }
}
