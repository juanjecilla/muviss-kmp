package com.codingpit.muviss

import com.codingpit.muviss.core.common.AppVersion
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iOS release string (#422, #431): the namespace is what lets the per-platform Sentry gates tell
 * the two stores apart. `fastlane/sentry_gate.rb` keeps the same string in `NAMESPACES`;
 * `fastlane/test/sentry_gate_test.rb` compares that file's source to this actual, so the two cannot
 * drift without a test failing.
 */
class IosCrashReleaseTest {

    @Test
    fun the_ios_namespace_is_the_application_id_plus_ios() {
        assertEquals("com.codingpit.muviss.ios", crashReleaseNamespace)
    }

    @Test
    fun the_release_a_crash_reports_uses_it() {
        assertEquals("com.codingpit.muviss.ios@1.2.3+45", MuvissCrashReporting.releaseOf(AppVersion("1.2.3", 45L)))
    }
}
