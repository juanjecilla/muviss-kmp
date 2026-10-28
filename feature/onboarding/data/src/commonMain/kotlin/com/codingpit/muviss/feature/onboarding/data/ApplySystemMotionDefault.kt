package com.codingpit.muviss.feature.onboarding.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.motion.SystemMotionPreference
import com.codingpit.muviss.core.database.AppSettingsQueries
import kotlinx.coroutines.withContext

/**
 * On a fresh install, turns the in-app animations switch off when the OS says the person wants less
 * motion (#394). Once per install, at the first launch, whatever screen it starts on.
 *
 * "Once" is its own mark, `appSettings.motionDefaultApplied`, not the introduction: a deep-link launch
 * skips the introduction and leaves Settings reachable, so a person can choose before it ever runs,
 * and a default keyed to the introduction would then overwrite that choice on the next plain launch.
 * `21.sqm` marks every install that predates it, so an upgrade keeps the switch it has. Without the
 * signal (desktop, web, or a person who did not ask) it changes nothing but the mark.
 */
class ApplySystemMotionDefault internal constructor(
    private val queries: AppSettingsQueries,
    private val dispatchers: AppDispatchers,
    private val flags: FeatureFlags,
    private val systemMotion: SystemMotionPreference,
) {
    suspend operator fun invoke() {
        val applied = withContext(dispatchers.io) {
            queries.ensureRow()
            queries.selectMotionDefaultApplied().awaitAsOneOrNull() ?: true
        }
        if (applied) return
        if (systemMotion.reduceMotion()) flags.setAnimationsEnabled(false)
        withContext(dispatchers.io) { queries.markMotionDefaultApplied() }
    }
}
