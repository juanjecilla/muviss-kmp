package com.codingpit.muviss.models

import kotlinx.serialization.Serializable

/**
 * Derived viewing status. Progress (episode ticks / seen flag) is the single
 * source of truth; this value is always a pure function of progress plus the
 * show's [ProductionStatus] — never stored as an independent, overridable field.
 *
 * - [NOT_STARTED]: saved, nothing seen.
 * - [WATCHING]: at least one episode seen, but not all aired episodes.
 * - [WATCHED]: all aired episodes seen, show still ongoing (more may come).
 * - [FINISHED]: all episodes seen and production has ended.
 *
 * Movies use only [NOT_STARTED] and [WATCHED].
 */
@Serializable
enum class WatchStatus {
    NOT_STARTED,
    WATCHING,
    WATCHED,
    FINISHED,
}
