import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves `verifyOssLicenses` goes red both ways (#562 missing, #616 stale) and that the hand-written font
 * tail is outside the comparison. Fixtures are literals, so no checked-in report or list can rot under them.
 */
class OssLicensesListTest {

    private fun report(vararg artifacts: Pair<String, String>): String = artifacts.joinToString(prefix = "[", postfix = "]") { (group, artifact) ->
        """{"groupId":"$group","artifactId":"$artifact","name":"$artifact","spdxLicenses":[{"identifier":"Apache-2.0","name":"Apache License 2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"}],"unknownLicenses":[]}"""
    }

    private fun entry(group: String, artifact: String, commented: Boolean = false): String =
        (if (commented) "    // " else "    ") + """OssLicense("$group", "$artifact", "$artifact", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),"""

    private fun source(vararg entries: String): String = listOf(
        "package com.example",
        "val ossLicenses: List<OssLicense> = listOf(",
        *entries,
        "    // Not a Maven dependency: the app-wide type family bundled as a Compose",
        "    // font resource in :core:designsystem.",
        """    OssLicense("com.schibsted", "schibsted-grotesk", "Schibsted Grotesk (font)", "SIL Open Font License 1.1", "https://openfontlicense.org"),""",
        ")",
        "",
    ).joinToString("\n")

    @Test
    fun a_list_that_matches_the_report_has_no_drift() {
        val drift = OssLicensesList.drift(report("a" to "x", "b" to "y"), source(entry("a", "x"), entry("b", "y")))
        assertTrue(drift.isEmpty)
        assertEquals(emptyList(), drift.missing)
        assertEquals(emptyList(), drift.stale)
    }

    @Test
    fun an_artifact_the_list_has_no_entry_for_is_missing() {
        val drift = OssLicensesList.drift(report("a" to "x", "b" to "y"), source(entry("a", "x")))
        assertEquals(listOf("b:y"), drift.missing)
        assertEquals(emptyList(), drift.stale)
        assertFalse(drift.isEmpty)
    }

    @Test
    fun an_entry_for_an_artifact_the_report_no_longer_lists_is_stale() {
        // The #616 case: the entry stays on About > Licenses after its dependency left the graph.
        val drift = OssLicensesList.drift(report("a" to "x"), source(entry("a", "x"), entry("c", "gone")))
        assertEquals(listOf("c:gone"), drift.stale)
        assertEquals(emptyList(), drift.missing)
        assertFalse(drift.isEmpty)
    }

    @Test
    fun the_hand_written_font_entry_is_not_reported_as_stale() {
        // It is not a Maven artifact, so the report never lists it; it must sit outside the comparison.
        val drift = OssLicensesList.drift(report("a" to "x"), source(entry("a", "x")))
        assertTrue(drift.isEmpty)
    }

    @Test
    fun a_commented_out_entry_does_not_count_as_listed() {
        val drift = OssLicensesList.drift(report("a" to "x"), source(entry("a", "x", commented = true)))
        assertEquals(listOf("a:x"), drift.missing)
        assertEquals(emptyList(), drift.stale)
    }

    @Test
    fun the_failure_message_names_both_directions_and_the_regenerate_task() {
        val message = OssLicensesList.failureMessage(OssLicensesList.Drift(missing = listOf("b:y"), stale = listOf("c:gone")))
        assertContains(message, "b:y")
        assertContains(message, "c:gone")
        assertContains(message, "no entry for 1 artifact(s)")
        assertContains(message, "no longer lists: c:gone")
        assertContains(message, "./gradlew :app:androidApp:regenerateOssLicenses")
    }

    @Test
    fun the_failure_message_names_only_the_direction_that_failed() {
        val message = OssLicensesList.failureMessage(OssLicensesList.Drift(missing = emptyList(), stale = listOf("c:gone")))
        assertContains(message, "c:gone")
        assertFalse(message.contains("no entry for"))
    }

    @Test
    fun regenerating_removes_stale_entries_and_adds_missing_ones_and_keeps_the_font() {
        val rep = report("a" to "x", "b" to "y")
        val stale = source(entry("a", "x"), entry("c", "gone"))
        val regenerated = OssLicensesList.regenerate(rep, stale)

        assertTrue(OssLicensesList.drift(rep, regenerated).isEmpty)
        assertFalse(regenerated.contains("\"gone\""))
        assertContains(regenerated, """OssLicense("com.schibsted", "schibsted-grotesk"""")
    }

    @Test
    fun regenerating_is_idempotent() {
        val rep = report("a" to "x", "b" to "y")
        val once = OssLicensesList.regenerate(rep, source(entry("a", "x")))
        assertEquals(once, OssLicensesList.regenerate(rep, once))
    }

    @Test
    fun a_source_without_its_generated_markers_fails_loudly() {
        val broken = "package com.example\nval other = 1\n"
        assertFailsWith<IllegalArgumentException> { OssLicensesList.drift(report("a" to "x"), broken) }
        assertFailsWith<IllegalArgumentException> { OssLicensesList.regenerate(report("a" to "x"), broken) }
    }
}
