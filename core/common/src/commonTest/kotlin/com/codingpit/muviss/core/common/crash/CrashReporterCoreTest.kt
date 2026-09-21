package com.codingpit.muviss.core.common.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReporterCoreTest {

    private class FakeBackend : CrashBackend {
        var starts = 0
        var lastConfig: CrashReportingConfig? = null
        var lastGate: CrashReportGate? = null
        val captured = mutableListOf<Throwable>()

        override fun start(config: CrashReportingConfig, gate: CrashReportGate) {
            starts++
            lastConfig = config
            lastGate = gate
        }

        override fun capture(throwable: Throwable) {
            captured += throwable
        }
    }

    private val config = CrashReportingConfig(
        dsn = "https://key@o1.ingest.sentry.io/1",
        environment = "production",
        release = "com.codingpit.muviss@1.2.3+45",
        dist = "45",
    )

    @Test
    fun a_backend_says_whether_the_platform_has_a_reporter() {
        assertTrue(CrashReporterCore(FakeBackend()).isAvailable)
        val web = object : CrashBackend {
            override val isAvailable = false
            override fun start(config: CrashReportingConfig, gate: CrashReportGate) = Unit
            override fun capture(throwable: Throwable) = Unit
        }
        assertFalse(CrashReporterCore(web).isAvailable)
    }

    @Test
    fun init_is_idempotent() {
        val backend = FakeBackend()
        val core = CrashReporterCore(backend)

        repeat(5) { core.init(config, enabled = true) }

        assertEquals(1, backend.starts)
    }

    @Test
    fun the_later_guard_call_cannot_overrule_the_consent_the_host_read() {
        val backend = FakeBackend()
        val core = CrashReporterCore(backend)

        core.init(config, enabled = false)
        core.init(config, enabled = true) // MuvissApp()'s guard

        assertFalse(backend.lastGate!!.enabled)
    }

    @Test
    fun a_blank_dsn_never_reaches_the_sdk_and_does_not_burn_the_one_start() {
        val backend = FakeBackend()
        val core = CrashReporterCore(backend)

        core.init(config.copy(dsn = ""), enabled = true)
        core.recordException(IllegalStateException("x"))
        assertEquals(0, backend.starts)
        assertTrue(backend.captured.isEmpty())

        core.init(config, enabled = true)
        assertEquals(1, backend.starts)
    }

    @Test
    fun the_backend_is_handed_the_config_it_needs_to_match_a_mapping() {
        val backend = FakeBackend()

        CrashReporterCore(backend).init(config, enabled = true)

        assertEquals(config, backend.lastConfig)
    }

    @Test
    fun a_recorded_exception_reaches_the_sdk_only_while_reporting_is_allowed() {
        val backend = FakeBackend()
        val core = CrashReporterCore(backend)
        core.init(config, enabled = true)
        val first = IllegalStateException("one")
        val second = IllegalStateException("two")
        val third = IllegalStateException("three")

        core.recordException(first)
        core.setEnabled(false)
        core.recordException(second)
        core.setEnabled(true)
        core.recordException(third)

        assertEquals(listOf<Throwable>(first, third), backend.captured)
    }

    @Test
    fun the_switch_is_the_same_gate_the_sdks_before_send_reads() {
        val backend = FakeBackend()
        val core = CrashReporterCore(backend)
        core.init(config, enabled = true)

        core.setEnabled(false)

        // beforeSend drops every event — including a real crash the SDK caught
        // itself — by reading exactly this.
        assertFalse(backend.lastGate!!.enabled)
    }

    @Test
    fun recording_before_init_does_nothing_and_does_not_throw() {
        val backend = FakeBackend()

        CrashReporterCore(backend).recordException(IllegalStateException("too early"))

        assertTrue(backend.captured.isEmpty())
    }

    @Test
    fun an_sdk_that_throws_never_takes_the_app_down() {
        val exploding = object : CrashBackend {
            override fun start(config: CrashReportingConfig, gate: CrashReportGate) = error("sdk init failed")

            override fun capture(throwable: Throwable) = error("sdk capture failed")
        }
        val core = CrashReporterCore(exploding)

        core.init(config, enabled = true)
        core.recordException(IllegalStateException("x"))
    }
}
