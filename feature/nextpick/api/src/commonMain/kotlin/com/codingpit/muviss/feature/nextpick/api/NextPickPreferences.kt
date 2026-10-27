package com.codingpit.muviss.feature.nextpick.api

import kotlinx.coroutines.flow.Flow

/**
 * How many questions a Pick Session may ask (EPIC 51, #403). Per device, set in Settings, and read by the session when it
 * opens. [DEFAULT] is [STANDARD]: the middle choice, which is what a person who never opens Settings gets.
 *
 * `maxQuestions` is the cap; a skipped question still counts against it, because the person was shown it (ADR 0032).
 */
enum class SessionLength(val maxQuestions: Int) {
    /** Up to two questions. */
    QUICK(2),

    /** Up to four questions. */
    STANDARD(4),

    /** No questions: straight to a pick. */
    NONE(0),
    ;

    companion object {
        val DEFAULT: SessionLength = STANDARD

        /** Tolerates anything unrecognised in storage: a preference must never crash the app. */
        fun fromStored(raw: String?): SessionLength = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * What a Next Pick looks like once the session has ended (EPIC 51, #401 renders it). Per device, set in Settings. [DEFAULT]
 * is [ONE_PICK], with "another" to step to the next candidate.
 */
enum class ResultMode {
    /** One pick at a time, with "another". */
    ONE_PICK,

    /** Three to five picks on one screen. */
    TOP_PICKS,

    /** A Triage deck seeded by the session. */
    DECK,
    ;

    companion object {
        val DEFAULT: ResultMode = ONE_PICK

        /** Tolerates anything unrecognised in storage: a preference must never crash the app. */
        fun fromStored(raw: String?): ResultMode = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * The Next Pick preferences that are enums, so they cannot live on `FeatureFlags` (`:core:common` cannot see a feature's
 * types). Implemented by `:feature:settings:data` over the `appSettings` row, bound once, and read by the Pick Session
 * (#400) and its result (#401) through this interface only.
 *
 * The Boolean completion prompt is `FeatureFlags.completionPromptEnabled`, not here. Per device and never synced or
 * exported; a write takes effect the next time a session opens or a Completion is detected, never mid-session.
 */
interface NextPickPreferences {
    val sessionLength: Flow<SessionLength>

    val resultMode: Flow<ResultMode>

    suspend fun setSessionLength(length: SessionLength)

    suspend fun setResultMode(mode: ResultMode)
}
