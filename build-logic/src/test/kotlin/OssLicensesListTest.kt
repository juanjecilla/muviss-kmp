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
    fun an_artifact_listed_twice_is_duplicated_even_though_the_sets_match() {
        val drift = OssLicensesList.drift(report("a" to "x"), source(entry("a", "x"), entry("a", "x")))
        assertEquals(listOf("a:x"), drift.duplicated)
        assertEquals(emptyList(), drift.missing)
        assertEquals(emptyList(), drift.stale)
        assertFalse(drift.isEmpty)
        assertContains(OssLicensesList.failureMessage(drift), "listed more than once: a:x")
    }

    @Test
    fun a_block_commented_entry_does_not_count_as_listed() {
        val drift = OssLicensesList.drift(report("a" to "x"), source(entry("a", "x"), "    /*", entry("c", "gone"), "    */"))
        assertTrue(drift.isEmpty)
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
        assertContains(message, "the list has no entry for 1 artifact(s) the licensee report lists")
        assertContains(message, "no longer lists: c:gone")
        assertContains(message, "./gradlew :app:androidApp:regenerateOssLicenses")
    }

    @Test
    fun the_failure_message_names_only_the_direction_that_failed() {
        val message = OssLicensesList.failureMessage(OssLicensesList.Drift(missing = emptyList(), stale = listOf("c:gone")))
        assertContains(message, "c:gone")
        assertFalse(message.contains("no entry for"))
    }

    // --- #666: the same coordinates with a changed name, licence or licence URL ---

    private val apacheUrl = "https://www.apache.org/licenses/LICENSE-2.0"

    /** A one-artifact report for `g:a` with the given name, licence and URL (null means licensee has none). */
    private fun reportOf(name: String?, licence: String?, url: String? = apacheUrl, identifier: String? = null): String {
        val urlJson = url?.let { "\"$it\"" } ?: "null"
        val nameJson = name?.let { "\"$it\"" } ?: "null"
        val licenceJson = licence?.let { "\"$it\"" } ?: "null"
        val identifierJson = identifier?.let { "\"$it\"" } ?: "null"
        return """[{"groupId":"g","artifactId":"a","name":$nameJson,"spdxLicenses":[{"identifier":$identifierJson,"name":$licenceJson,"url":$urlJson}],"unknownLicenses":[]}]"""
    }

    /** The one entry for `g:a` the list holds, written with the given name, licence and URL. */
    private fun listingOf(name: String, licence: String, url: String? = apacheUrl): String {
        val urlLiteral = url?.let { "\"$it\"" } ?: "null"
        return """    OssLicense("g", "a", "$name", "$licence", $urlLiteral),"""
    }

    @Test
    fun a_licence_change_at_the_same_coordinates_is_changed_and_red() {
        // The #666 case: the coordinates survive a bump, the licence does not, and the screen would keep the old one.
        val drift = OssLicensesList.drift(reportOf("a", "MIT License", "https://opensource.org/licenses/MIT"), source(listingOf("a", "Apache License 2.0")))
        assertEquals(listOf("g:a"), drift.changed)
        assertEquals(emptyList(), drift.missing)
        assertEquals(emptyList(), drift.stale)
        assertFalse(drift.isEmpty)
        assertContains(OssLicensesList.failureMessage(drift), "name, licence or licence URL differs from the licensee report: g:a")
    }

    @Test
    fun a_name_change_at_the_same_coordinates_is_changed() {
        val drift = OssLicensesList.drift(reportOf("Renamed", "Apache License 2.0"), source(listingOf("a", "Apache License 2.0")))
        assertEquals(listOf("g:a"), drift.changed)
        assertFalse(drift.isEmpty)
    }

    @Test
    fun a_licence_url_change_at_the_same_coordinates_is_changed() {
        val drift = OssLicensesList.drift(reportOf("a", "Apache License 2.0", "https://example.com/new"), source(listingOf("a", "Apache License 2.0")))
        assertEquals(listOf("g:a"), drift.changed)
    }

    @Test
    fun an_unchanged_report_with_the_same_coordinates_is_not_changed_and_green() {
        val drift = OssLicensesList.drift(reportOf("a", "Apache License 2.0"), source(listingOf("a", "Apache License 2.0")))
        assertTrue(drift.isEmpty)
        assertEquals(emptyList(), drift.changed)
    }

    @Test
    fun the_check_compares_against_the_generators_fallbacks_not_the_raw_report() {
        // The report has no name, no licence name and no URL, so the generator writes the coordinates, the identifier and null.
        val rep = reportOf(name = null, licence = null, url = null, identifier = "MIT")
        val generated = OssLicensesList.regenerate(rep, source(entry("seed", "entry")))
        assertContains(generated, """OssLicense("g", "a", "g:a", "MIT", null),""")
        assertTrue(OssLicensesList.drift(rep, generated).isEmpty)

        // A list written with the bare artifact name, not the generator's fallback, is a change.
        val byHand = source("""    OssLicense("g", "a", "a", "MIT", null),""")
        assertEquals(listOf("g:a"), OssLicensesList.drift(rep, byHand).changed)
    }

    @Test
    fun regenerating_clears_a_changed_entry() {
        val rep = reportOf("a", "MIT License", "https://opensource.org/licenses/MIT")
        val stale = source(listingOf("a", "Apache License 2.0"))
        val regenerated = OssLicensesList.regenerate(rep, stale)
        assertTrue(OssLicensesList.drift(rep, regenerated).isEmpty)
        assertContains(regenerated, """OssLicense("g", "a", "a", "MIT License", "https://opensource.org/licenses/MIT"),""")
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

    // --- regenerate's handling of one licensee artifact record (#616 CodeRabbit fixes) ---

    /** Regenerates a source whose only generated entry is [artifactJson], returning the rewritten source. */
    private fun regeneratedFrom(artifactJson: String): String =
        OssLicensesList.regenerate("[$artifactJson]", source(entry("seed", "entry")))

    @Test
    fun a_licence_listed_only_under_unknown_licences_is_kept() {
        // Fix 2: licensee can report the licence under unknownLicenses; it must not be dropped as "no licence".
        val out = regeneratedFrom(
            """{"groupId":"g","artifactId":"a","name":"A","spdxLicenses":[],"unknownLicenses":[{"name":"Custom Licence","url":"https://example.com/l"}]}""",
        )
        assertContains(out, """OssLicense("g", "a", "A", "Custom Licence", "https://example.com/l"),""")
    }

    @Test
    fun a_licence_split_across_both_collections_is_still_one_licence_and_refused_when_it_is_two() {
        // Fix 2: the size check counts both collections together, so two licences are refused rather than one being shown.
        assertFailsWith<IllegalArgumentException> {
            regeneratedFrom(
                """{"groupId":"g","artifactId":"a","name":"A","spdxLicenses":[{"name":"MIT"}],"unknownLicenses":[{"name":"Custom"}]}""",
            )
        }
    }

    @Test
    fun a_null_artifact_name_falls_back_to_group_and_artifact() {
        // Fix 3.
        val out = regeneratedFrom("""{"groupId":"g","artifactId":"a","name":null,"spdxLicenses":[{"name":"MIT","url":"https://x"}],"unknownLicenses":[]}""")
        assertContains(out, """OssLicense("g", "a", "g:a", "MIT", "https://x"),""")
    }

    @Test
    fun a_null_licence_name_falls_back_to_its_identifier() {
        // Fix 3.
        val out = regeneratedFrom("""{"groupId":"g","artifactId":"a","name":"A","spdxLicenses":[{"name":null,"identifier":"MIT","url":"https://x"}],"unknownLicenses":[]}""")
        assertContains(out, """OssLicense("g", "a", "A", "MIT", "https://x"),""")
    }

    @Test
    fun a_licence_with_no_name_or_identifier_falls_back_to_its_url() {
        // Fix 3.
        val out = regeneratedFrom("""{"groupId":"g","artifactId":"a","name":"A","spdxLicenses":[{"name":null,"identifier":null,"url":"https://x"}],"unknownLicenses":[]}""")
        assertContains(out, """OssLicense("g", "a", "A", "https://x", "https://x"),""")
    }

    @Test
    fun a_licence_with_nothing_at_all_is_unknown_and_has_no_url() {
        // Fix 3: the last fallback, and a null url is written as the Kotlin null literal.
        val out = regeneratedFrom("""{"groupId":"g","artifactId":"a","name":"A","spdxLicenses":[{"name":null,"identifier":null,"url":null}],"unknownLicenses":[]}""")
        assertContains(out, """OssLicense("g", "a", "A", "Unknown licence", null),""")
    }

    @Test
    fun line_breaks_and_dollar_signs_in_generated_strings_are_escaped() {
        // Fix 4: a raw newline or CR in the source would split the generated Kotlin string literal and break compilation.
        val out = regeneratedFrom("""{"groupId":"g","artifactId":"a","name":"a\nb\rc${'$'}d","spdxLicenses":[{"name":"MIT","url":"https://x"}],"unknownLicenses":[]}""")
        assertContains(out, "\"a\\nb\\rc\\\$d\"")
        assertFalse(out.contains("a\nb"))
    }
}
