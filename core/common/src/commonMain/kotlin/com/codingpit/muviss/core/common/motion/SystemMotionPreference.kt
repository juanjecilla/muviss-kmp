package com.codingpit.muviss.core.common.motion

/**
 * Whether the person told their OS they want less motion (Android's animator duration scale at 0, iOS's
 * Reduce Motion, a browser's `prefers-reduced-motion`). Read once, on a fresh install, to decide the
 * *default* of the in-app animations switch (#394); after that the switch is the person's own and this
 * is never consulted again, so an explicit choice in Settings always wins.
 *
 * The hosts that have a signal bind one (Android, iOS); everywhere else nothing reduces.
 */
fun interface SystemMotionPreference {
    fun reduceMotion(): Boolean

    companion object {
        /** No signal on this platform: motion stays on. */
        val None = SystemMotionPreference { false }
    }
}
