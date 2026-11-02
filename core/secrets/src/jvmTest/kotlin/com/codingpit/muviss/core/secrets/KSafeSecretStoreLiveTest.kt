package com.codingpit.muviss.core.secrets

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The real KSafe on this machine's JVM: a round trip through the OS secret store
 * (macOS Keychain, Windows DPAPI, Linux Secret Service, or KSafe's software fallback).
 *
 * **Skipped unless `MUVISS_KSAFE_LIVE=1`.** It writes a key-material entry into the
 * developer's own keychain, which a unit test run must never do unasked, and CI has
 * no keychain to speak of. Run it once per KSafe upgrade:
 * `MUVISS_KSAFE_LIVE=1 ./gradlew :core:secrets:jvmTest`. `clearAll` removes what it made.
 */
class KSafeSecretStoreLiveTest {

    @Test
    fun a_secret_survives_a_new_instance_and_clear_all_removes_it() = runBlocking {
        assumeTrue("set MUVISS_KSAFE_LIVE=1 to run against the real OS secret store", System.getenv("MUVISS_KSAFE_LIVE") == "1")
        val first = KSafeSecretStore(createPlatformKSafe())
        first.put("tmdb.ownKey", "a".repeat(32))

        val second = KSafeSecretStore(createPlatformKSafe())
        assertEquals("a".repeat(32), second.get("tmdb.ownKey"))

        second.clearAll()
        assertNull(KSafeSecretStore(createPlatformKSafe()).get("tmdb.ownKey"))
    }
}
