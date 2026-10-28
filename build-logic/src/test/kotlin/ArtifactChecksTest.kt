import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the two artifact checks go red on a bad artifact (#490). The fixtures are built here from
 * literals, so there is no committed mapping or archive to rot into a permanently failing file.
 */
class ArtifactChecksTest {

    private val canaries = listOf("com.example.NotFound", "com.example.Offline")

    private fun mapping(vararg classes: String) = classes.flatMap { listOf("$it -> a.b:", "    int field -> a") }.asSequence()

    @Test
    fun a_mapping_that_keeps_every_canary_passes() {
        assertEquals(emptyList(), ArtifactChecks.mergedCanaries(mapping("com.example.NotFound", "com.example.Offline", "other.Thing"), canaries))
    }

    @Test
    fun a_canary_r8_merged_away_is_reported_by_name() {
        // R8 folded Offline into another class: its own line is gone from the mapping.
        val merged = ArtifactChecks.mergedCanaries(mapping("com.example.NotFound", "other.Thing"), canaries)
        assertEquals(listOf("com.example.Offline"), merged)
    }

    @Test
    fun an_indented_member_line_never_counts_as_a_class() {
        val lines = sequenceOf("com.example.NotFound -> a:", "    com.example.Offline -> b")
        assertEquals(listOf("com.example.Offline"), ArtifactChecks.mergedCanaries(lines, canaries))
    }

    @Test
    fun an_empty_mapping_reports_every_canary() {
        assertEquals(canaries, ArtifactChecks.mergedCanaries(emptySequence(), canaries))
    }

    private val prefix = "assets/composeResources/"
    private val expected = listOf("com.x.core.designsystem.generated.resources", "com.x.feature.search.ui.generated.resources")

    @Test
    fun an_archive_with_every_modules_resources_passes() {
        val entries = sequenceOf(
            "${prefix}com.x.core.designsystem.generated.resources/font/a.ttf",
            "${prefix}com.x.feature.search.ui.generated.resources/values/strings.xml",
            "classes.dex",
        )
        assertTrue(ArtifactChecks.missingComposeResources(entries, prefix, expected).isEmpty())
    }

    @Test
    fun an_archive_without_a_modules_resources_names_it() {
        // The 2026-10 incident: androidResources was off, so strings and the font never shipped.
        val entries = sequenceOf("${prefix}com.x.core.designsystem.generated.resources/font/a.ttf", "classes.dex")
        assertEquals(setOf("com.x.feature.search.ui.generated.resources"), ArtifactChecks.missingComposeResources(entries, prefix, expected))
    }

    @Test
    fun resources_under_the_wrong_prefix_do_not_count() {
        val entries = sequenceOf("base/assets/composeResources/com.x.core.designsystem.generated.resources/font/a.ttf")
        assertEquals(expected.toSet(), ArtifactChecks.missingComposeResources(entries, prefix, expected))
    }

    @Test
    fun a_real_zip_built_at_test_time_is_read_the_way_the_task_reads_it() {
        val zip = File.createTempFile("fixture", ".apk").apply { deleteOnExit() }
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("${prefix}com.x.core.designsystem.generated.resources/font/a.ttf"))
            out.write(1)
            out.closeEntry()
        }
        val missing = ZipFile(zip).use { ArtifactChecks.missingComposeResources(it.entries().asSequence().map { e -> e.name }, prefix, expected) }
        assertEquals(setOf("com.x.feature.search.ui.generated.resources"), missing)
    }
}
