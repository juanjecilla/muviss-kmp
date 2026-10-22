package com.codingpit.muviss.feature.onboarding.data

import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.motion.SystemMotionPreference

/**
 * On a fresh install, turns the in-app animations switch off when the OS says the person wants less
 * motion (#394). A default for the first run only: it runs when the introduction starts, the one place
 * a fresh install is knowable (`onboardingCompleted` is false only there, an upgrade is marked done by
 * `17.sqm`), and nothing else ever calls it, so a person's own choice in Settings is never overridden.
 * Without the signal (desktop, web, or a person who did not ask) it changes nothing.
 */
class ApplySystemMotionDefault(
    private val flags: FeatureFlags,
    private val systemMotion: SystemMotionPreference,
) {
    suspend operator fun invoke() {
        if (systemMotion.reduceMotion()) flags.setAnimationsEnabled(false)
    }
}
