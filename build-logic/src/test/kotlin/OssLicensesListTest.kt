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
        assertContains(message, "the list has no entry for 1 artifact(s) the licensee reports list")
        assertContains(message, "no licensee report lists any more: c:gone")
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
        assertContains(OssLicensesList.failureMessage(drift), "name, licence or licence URL differs from the licensee reports: g:a")
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
    fun a_licence_split_across_both_collections_counts_both_and_three_are_refused() {
        // Fix 2 (#615): both collections count together. Two records are now shown (see the two-licence tests below),
        // so the refusal starts at three.
        val two = regeneratedFrom(
            """{"groupId":"g","artifactId":"a","name":"A","spdxLicenses":[{"name":"MIT"}],"unknownLicenses":[{"name":"Custom"}]}""",
        )
        assertContains(two, """OssLicense("g", "a", "A", "MIT", null, "Custom", null),""")
        assertFailsWith<IllegalArgumentException> {
            regeneratedFrom(
                """{"groupId":"g","artifactId":"a","name":"A","spdxLicenses":[{"name":"MIT"},{"name":"BSD"}],"unknownLicenses":[{"name":"Custom"}]}""",
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

    // --- #615: the union of every shipped target's report, and up to two licences per artifact ---

    private val mitUrl = "https://opensource.org/license/mit"

    /** A report naming [coordinates] (group:artifact pairs) with one Apache-2.0 licence each. */
    private fun reportWith(vararg coordinates: String): String = coordinates.joinToString(prefix = "[", postfix = "]") { coordinate ->
        val (group, artifact) = coordinate.split(":")
        """{"groupId":"$group","artifactId":"$artifact","name":"$artifact","spdxLicenses":[{"identifier":"Apache-2.0","name":"Apache License 2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"}],"unknownLicenses":[]}"""
    }

    /** One artifact `g:a` with the given licence records (name to url), in one report. */
    private fun artifactWith(name: String?, vararg licences: Pair<String, String?>): String {
        val nameJson = name?.let { "\"$it\"" } ?: "null"
        val records = licences.joinToString(",") { (licence, url) -> """{"name":"$licence","url":${url?.let { "\"$it\"" } ?: "null"}}""" }
        return """[{"groupId":"g","artifactId":"a","name":$nameJson,"spdxLicenses":[$records],"unknownLicenses":[]}]"""
    }

    private fun reports(vararg pairs: Pair<String, String>): List<OssLicensesList.Report> = pairs.map { (label, json) -> OssLicensesList.Report(label, json) }

    private fun listingFor(vararg args: String): String = """    OssLicense(${args.joinToString(", ")}),"""

    @Test
    fun the_union_lists_an_artifact_only_one_target_ships_and_names_that_target() {
        // The #655 note: a desktop-only artifact must not read as stale against the Android report alone.
        val drift = OssLicensesList.drift(
            reports("Android release" to reportWith("a:x"), "desktop" to reportWith("a:x", "skiko:skiko")),
            source(entry("a", "x")),
        )
        assertEquals(listOf("skiko:skiko"), drift.missing)
        assertEquals(emptyList(), drift.stale)
        assertEquals(listOf("desktop"), drift.sources["skiko:skiko"])
        assertContains(OssLicensesList.failureMessage(drift), "skiko:skiko (desktop)")
    }

    @Test
    fun an_entry_no_report_lists_is_stale_even_when_one_of_several_reports_lists_it() {
        val listed = source(entry("a", "x"), entry("c", "gone"))
        val drift = OssLicensesList.drift(reports("android" to reportWith("a:x"), "ios" to reportWith("a:x")), listed)
        assertEquals(listOf("c:gone"), drift.stale)
    }

    @Test
    fun an_artifact_two_targets_ship_under_the_same_coordinates_is_one_entry_listing_both_sources() {
        val rep = reports("android" to reportWith("a:x"), "desktop" to reportWith("a:x"))
        val drift = OssLicensesList.drift(rep, source(entry("a", "x")))
        assertTrue(drift.isEmpty)
        assertEquals(listOf("android", "desktop"), drift.sources["a:x"])
    }

    @Test
    fun a_name_that_differs_between_reports_is_refused_naming_both() {
        // The screen shows one name per artifact, so the reports must agree on it.
        val error = assertFailsWith<IllegalArgumentException> {
            OssLicensesList.drift(
                reports("android" to artifactWith("Alpha", "Apache License 2.0" to null), "desktop" to artifactWith("Beta", "Apache License 2.0" to null)),
                source(),
            )
        }
        assertContains(error.message!!, "\"Alpha\"")
        assertContains(error.message!!, "\"Beta\"")
    }

    @Test
    fun two_different_licences_from_two_targets_are_two_records_and_the_list_writes_both() {
        // The owner's decision (#615): an artifact may carry two licence records. Android says MIT, desktop says Apache.
        val rep = reports(
            "android" to artifactWith("A", "MIT" to mitUrl),
            "desktop" to artifactWith("A", "Apache License 2.0" to "https://www.apache.org/licenses/LICENSE-2.0"),
        )
        val regenerated = OssLicensesList.regenerate(rep, source(entry("seed", "entry")))
        assertContains(
            regenerated,
            listingFor("\"g\"", "\"a\"", "\"A\"", "\"MIT\"", "\"$mitUrl\"", "\"Apache License 2.0\"", "\"https://www.apache.org/licenses/LICENSE-2.0\""),
        )
        assertTrue(OssLicensesList.drift(rep, regenerated).isEmpty)
    }

    @Test
    fun a_third_licence_across_the_reports_is_refused() {
        // More than two records would not fit the Licenses row, so the build stops and says so (#615).
        val error = assertFailsWith<IllegalArgumentException> {
            OssLicensesList.regenerate(
                reports(
                    "android" to artifactWith("A", "MIT" to mitUrl),
                    "desktop" to artifactWith("A", "Apache License 2.0" to null),
                    "iOS" to artifactWith("A", "BSD-3-Clause" to null),
                ),
                source(),
            )
        }
        assertContains(error.message!!, "g:a has 3 licences across android, desktop, iOS")
    }

    @Test
    fun an_artifact_with_two_licences_in_one_report_is_listed_with_both() {
        val listed = source(listingFor("\"g\"", "\"a\"", "\"A\"", "\"MIT\"", "\"$mitUrl\"", "\"Apache\"", "null"))
        assertTrue(OssLicensesList.drift(artifactWith("A", "MIT" to mitUrl), listed).changed.isNotEmpty(), "a second licence the report does not have is a change")
        val withBoth = reports("only" to artifactWith("A", "MIT" to mitUrl, "Apache" to null))
        assertTrue(OssLicensesList.drift(withBoth, listed).isEmpty)
    }

    @Test
    fun a_second_licence_whose_url_changes_is_changed() {
        val listed = source(listingFor("\"g\"", "\"a\"", "\"A\"", "\"MIT\"", "\"$mitUrl\"", "\"Apache\"", "\"https://old\""))
        val drift = OssLicensesList.drift(reports("only" to artifactWith("A", "MIT" to mitUrl, "Apache" to "https://new")), listed)
        assertEquals(listOf("g:a"), drift.changed)
    }

    @Test
    fun a_commented_out_two_licence_entry_does_not_count_as_listed() {
        val listed = source("    // " + listingFor("\"g\"", "\"a\"", "\"A\"", "\"MIT\"", "\"$mitUrl\"", "\"Apache\"", "null").trimStart())
        assertEquals(listOf("g:a"), OssLicensesList.drift(reports("only" to artifactWith("A", "MIT" to mitUrl)), listed).missing)
    }

    @Test
    fun a_union_regenerated_with_two_licences_is_idempotent() {
        val rep = reports("android" to artifactWith("A", "MIT" to mitUrl), "desktop" to artifactWith("A", "Apache" to null))
        val once = OssLicensesList.regenerate(rep, source())
        assertEquals(once, OssLicensesList.regenerate(rep, once))
    }

}
