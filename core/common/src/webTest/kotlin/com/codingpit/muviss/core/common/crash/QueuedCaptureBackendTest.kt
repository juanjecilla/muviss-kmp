package com.codingpit.muviss.core.common.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [QueuedCaptureBackend]'s queuing and gating without a browser — the
 * same reason `SchemaStepTest` covers `SchemaEnsuringDriver`'s decision
 * without one: everything [WebCrashBackend] actually needs a browser for
 * ([SentryJs]'s `js(...)`-bodied actuals) is faked out here as plain lambdas.
 */
class QueuedCaptureBackendTest {

    private val config = CrashReportingConfig(
        dsn = "https://key@o1.ingest.sentry.io/1",
        environment = "production",
        release = "com.codingpit.muviss@1.2.3+45",
        dist = "45",
    )

    private class Recorder {
        var onLoad: (() -> Unit)? = null
        var initCalls = 0
        val captured = mutableListOf<Triple<String, String, String>>()

        fun backend() = QueuedCaptureBackend(
            load = { _, callback -> onLoad = callback },
            init = { _, _, _, _, _, _ -> initCalls++ },
            captureException = { type, message, stack -> captured += Triple(type, message, stack) },
        )
    }

    @Test
    fun a_crash_reported_before_the_script_loads_is_queued_not_dropped() {
        val recorder = Recorder()
        val backend = recorder.backend()

        backend.start(config, CrashReportGate())
        backend.capture(IllegalStateException("too early"))

        assertTrue(recorder.captured.isEmpty())
    }

    @Test
    fun the_queued_crash_reaches_sentry_once_the_script_finishes_loading() {
        val recorder = Recorder()
        val backend = recorder.backend()
        backend.start(config, CrashReportGate())
        backend.capture(IllegalStateException("too early"))

        recorder.onLoad!!.invoke()

        assertEquals(1, recorder.captured.size)
        assertEquals("IllegalStateException", recorder.captured.single().first)
    }

    @Test
    fun a_crash_reported_after_the_script_has_loaded_is_sent_immediately() {
        val recorder = Recorder()
        val backend = recorder.backend()
        backend.start(config, CrashReportGate())
        recorder.onLoad!!.invoke()

        backend.capture(IllegalStateException("late"))

        assertEquals(1, recorder.captured.size)
    }

    @Test
    fun init_only_runs_once_the_script_has_loaded() {
        val recorder = Recorder()
        val backend = recorder.backend()

        backend.start(config, CrashReportGate())
        assertEquals(0, recorder.initCalls)

        recorder.onLoad!!.invoke()
        assertEquals(1, recorder.initCalls)
    }

    @Test
    fun the_message_and_stack_are_scrubbed_before_they_ever_reach_sentry() {
        val key = "0123456789abcdef0123456789abcdef"
        val recorder = Recorder()
        val backend = recorder.backend()
        backend.start(config, CrashReportGate())
        recorder.onLoad!!.invoke()

        backend.capture(IllegalStateException("failed for ?api_key=$key"))

        val (_, message, _) = recorder.captured.single()
        assertTrue(key !in message)
    }
}
