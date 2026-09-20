package com.codingpit.muviss.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The whole truth table of [AutoSyncPolicy]: build flag x user switch x
 * session x entitlement x trigger. The named tests are the rows a reader wants
 * to see spelled out; the sweep at the bottom checks every one of the 80
 * combinations against the two invariants the ADR states, so a row nobody
 * thought to name cannot drift.
 */
class AutoSyncPolicyTest {

    private fun decide(
        trigger: SyncTrigger,
        available: Boolean = true,
        switchOn: Boolean = true,
        signedIn: Boolean = true,
        entitled: Boolean = true,
    ) = AutoSyncPolicy.decide(trigger, available, switchOn, signedIn, entitled)

    private val automatic = SyncTrigger.entries.filter { it.isAutomatic }

    @Test
    fun manual_runs_with_the_switch_off() {
        assertEquals(SyncDecision.Run, decide(SyncTrigger.Manual, switchOn = false))
    }

    @Test
    fun manual_runs_in_a_build_without_background_sync() {
        assertEquals(SyncDecision.Run, decide(SyncTrigger.Manual, available = false, switchOn = false))
    }

    @Test
    fun manual_still_needs_a_session_and_an_entitlement() {
        assertEquals(SyncDecision.NotSignedIn, decide(SyncTrigger.Manual, signedIn = false))
        assertEquals(SyncDecision.NotEntitled, decide(SyncTrigger.Manual, entitled = false))
    }

    @Test
    fun every_automatic_trigger_runs_when_all_four_gates_pass() {
        automatic.forEach { assertEquals(SyncDecision.Run, decide(it), "$it") }
    }

    @Test
    fun every_automatic_trigger_is_disabled_when_the_switch_is_off() {
        automatic.forEach { assertEquals(SyncDecision.Disabled, decide(it, switchOn = false), "$it") }
    }

    @Test
    fun every_automatic_trigger_is_disabled_when_the_build_does_not_ship_background_sync() {
        automatic.forEach { assertEquals(SyncDecision.Disabled, decide(it, available = false), "$it") }
    }

    @Test
    fun a_switch_left_on_cannot_outvote_the_build_flag() {
        automatic.forEach { assertEquals(SyncDecision.Disabled, decide(it, available = false, switchOn = true), "$it") }
    }

    @Test
    fun disabled_is_reported_ahead_of_signed_out_and_unentitled() {
        // Nothing about an automatic trigger that is switched off should look
        // like "the user needs to sign in" or "needs to pay".
        automatic.forEach {
            assertEquals(SyncDecision.Disabled, decide(it, switchOn = false, signedIn = false, entitled = false), "$it")
        }
    }

    @Test
    fun a_switched_on_automatic_trigger_without_a_session_is_not_signed_in() {
        automatic.forEach { assertEquals(SyncDecision.NotSignedIn, decide(it, signedIn = false), "$it") }
    }

    @Test
    fun a_switched_on_signed_in_but_unentitled_automatic_trigger_is_not_entitled() {
        automatic.forEach { assertEquals(SyncDecision.NotEntitled, decide(it, entitled = false), "$it") }
    }

    @Test
    fun signed_out_outranks_unentitled() {
        SyncTrigger.entries.forEach { assertEquals(SyncDecision.NotSignedIn, decide(it, signedIn = false, entitled = false), "$it") }
    }

    private data class Row(val trigger: SyncTrigger, val available: Boolean, val switchOn: Boolean, val signedIn: Boolean, val entitled: Boolean)

    private fun allRows(): List<Row> {
        val flags = listOf(true, false)
        return SyncTrigger.entries.flatMap { trigger ->
            flags.flatMap { available ->
                flags.flatMap { switchOn ->
                    flags.flatMap { signedIn -> flags.map { entitled -> Row(trigger, available, switchOn, signedIn, entitled) } }
                }
            }
        }
    }

    private fun check(row: Row) {
        val decision = decide(row.trigger, row.available, row.switchOn, row.signedIn, row.entitled)
        val label = row.toString()
        // Nothing runs without a session and an entitlement, whatever asked.
        if (decision == SyncDecision.Run) assertEquals(true, row.signedIn && row.entitled, label)
        // An automatic trigger never runs unless the build AND the user allowed it.
        if (row.trigger.isAutomatic && decision == SyncDecision.Run) assertEquals(true, row.available && row.switchOn, label)
        // A manual trigger's answer does not depend on either of the two.
        if (!row.trigger.isAutomatic) assertEquals(decide(row.trigger, true, true, row.signedIn, row.entitled), decision, label)
    }

    @Test
    fun the_whole_table_obeys_the_two_invariants() {
        val rows = allRows()
        assertEquals(SyncTrigger.entries.size * 16, rows.size)
        rows.forEach(::check)
    }
}
