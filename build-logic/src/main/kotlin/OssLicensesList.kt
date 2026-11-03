/**
 * The decisions behind About > Licenses coverage (#562, #616, #666), apart from the Gradle task wiring so a test can
 * build the red case from literals. `OssLicenses.kt` is checked in and generated from licensee's release report;
 * this compares the two in both directions, compares what each entry says, and rewrites the generated part. The
 * hand-written tail below the generated entries (the bundled font, which is not a Maven artifact) is outside every
 * comparison.
 */
object OssLicensesList {
    private const val LIST_HEADER = "val ossLicenses: List<OssLicense> = listOf("
    private const val MANUAL_TAIL = "    // Not a Maven dependency"

    // The five arguments of an entry, each as Kotlin source (a quoted literal, or `null` for the URL). Anchored at
    // the start of a line, so an entry that is commented out does not count as listed.
    private val entryRegex = Regex(
        """^\s*OssLicense\(("(?:[^"\\]|\\.)*"),\s*("(?:[^"\\]|\\.)*"),\s*("(?:[^"\\]|\\.)*"),\s*("(?:[^"\\]|\\.)*"),\s*("(?:[^"\\]|\\.)*"|null)\)""",
        RegexOption.MULTILINE,
    )
    // An entry inside /* ... */ is not listed either, any more than one behind `//` (which `^\s*` already skips).
    private val blockComment = Regex("""/\*[\s\S]*?\*/""")

    /**
     * One entry as it appears in the file: the five arguments as written, Kotlin source text, so [key] is the
     * coordinates as the file spells them and [arguments] compare exactly against what the generator writes.
     */
    private class Listed(val arguments: List<String>) {
        val key: String get() = "${arguments[0].removeSurrounding("\"")}:${arguments[1].removeSurrounding("\"")}"
    }

    /**
     * One entry as the generator writes it from the report. [arguments] is the single place the generated text is
     * built: [regenerate] renders it and [drift] compares a listed entry against it, so the check cannot disagree
     * with the generator about a name, a licence or a URL.
     */
    private data class Written(val group: String, val artifact: String, val name: String, val licence: String, val url: String?) {
        val key: String get() = "$group:$artifact"
        val arguments: List<String>
            get() = listOf(
                kotlinString(group),
                kotlinString(artifact),
                kotlinString(name),
                kotlinString(licence),
                url?.let { kotlinString(it) } ?: "null",
            )
    }

    /**
     * Artifacts the list has no entry for, entries naming an artifact the report no longer lists, artifacts listed
     * twice, and entries whose name, licence or licence URL differ from the report. All sorted.
     */
    data class Drift(
        val missing: List<String>,
        val stale: List<String>,
        val duplicated: List<String> = emptyList(),
        val changed: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = missing.isEmpty() && stale.isEmpty() && duplicated.isEmpty() && changed.isEmpty()
    }

    private fun kotlinString(value: String): String = "\"" +
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("\n", "\\n")
            .replace("\r", "\\r") +
        "\""

    @Suppress("UNCHECKED_CAST")
    private fun artifacts(reportJson: String): List<Map<String, Any?>> =
        (groovy.json.JsonSlurper().parseText(reportJson) as List<*>).map { it as Map<String, Any?> }

    /** `group:artifact` for every artifact in licensee's `artifacts.json`. */
    fun reportedKeys(reportJson: String): Set<String> =
        artifacts(reportJson).map { "${it["groupId"]}:${it["artifactId"]}" }.toSet()

    /** The generated section of [source] (between the list header and the hand-written tail), by `group:artifact`. */
    fun listedKeys(source: String): Set<String> = listedEntries(source).map { it.key }.toSet()

    // In order and with repeats: a set would hide a coordinate listed twice, which the Licenses screen's lazy
    // list cannot show (both rows would share one key).
    private fun listedEntries(source: String): List<Listed> =
        entryRegex.findAll(generatedSection(source).replace(blockComment, "")).map { match ->
            Listed(match.groupValues.drop(1))
        }.toList()

    private fun generatedSection(source: String): String {
        require(LIST_HEADER in source && MANUAL_TAIL in source) {
            "OssLicenses.kt lost its generated-list markers ($LIST_HEADER / $MANUAL_TAIL); restore them before regenerating"
        }
        return source.substring(source.indexOf(LIST_HEADER) + LIST_HEADER.length, source.indexOf(MANUAL_TAIL))
    }

    /** What the generator writes for each artifact in the report, sorted by coordinates. */
    private fun written(reportJson: String): List<Written> =
        artifacts(reportJson)
            .sortedWith(compareBy({ it["groupId"] as String }, { it["artifactId"] as String }))
            .map { artifact ->
                // licensee can report both collections for one artifact; count them together.
                val licenses = ((artifact["spdxLicenses"] as? List<*>).orEmpty()) + ((artifact["unknownLicenses"] as? List<*>).orEmpty())
                require(licenses.size == 1) { "${artifact["groupId"]}:${artifact["artifactId"]} has ${licenses.size} licences; decide how to show it" }
                val license = licenses.single() as Map<*, *>
                val url = license["url"] as String?
                // licensee allows both names to be null: fall back to the coordinates and the licence's id or URL.
                val coordinates = "${artifact["groupId"]}:${artifact["artifactId"]}"
                Written(
                    group = artifact["groupId"] as String,
                    artifact = artifact["artifactId"] as String,
                    name = artifact["name"] as String? ?: coordinates,
                    licence = license["name"] as String? ?: license["identifier"] as String? ?: url ?: "Unknown licence",
                    url = url,
                )
            }

    fun drift(reportJson: String, source: String): Drift {
        val reported = written(reportJson)
        val reportedKeys = reported.map { it.key }.toSet()
        val reportedByKey = reported.associateBy { it.key }
        val entries = listedEntries(source)
        val listed = entries.map { it.key }.toSet()
        return Drift(
            missing = (reportedKeys - listed).sorted(),
            stale = (listed - reportedKeys).sorted(),
            duplicated = entries.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys.sorted(),
            changed = entries
                .filter { entry -> reportedByKey[entry.key]?.let { it.arguments != entry.arguments } == true }
                .map { it.key }
                .distinct()
                .sorted(),
        )
    }

    /** The message `verifyOssLicenses` fails with: names every artifact on each side and says how to regenerate. */
    fun failureMessage(drift: Drift): String {
        val parts = buildList {
            if (drift.missing.isNotEmpty()) {
                add("the list has no entry for ${drift.missing.size} artifact(s) the licensee report lists: ${drift.missing.joinToString()}")
            }
            if (drift.stale.isNotEmpty()) {
                add("${drift.stale.size} entry(ies) for artifact(s) the licensee report no longer lists: ${drift.stale.joinToString()}")
            }
            if (drift.duplicated.isNotEmpty()) {
                add("${drift.duplicated.size} artifact(s) listed more than once: ${drift.duplicated.joinToString()}")
            }
            if (drift.changed.isNotEmpty()) {
                add("${drift.changed.size} entry(ies) whose name, licence or licence URL differs from the licensee report: ${drift.changed.joinToString()}")
            }
        }
        return "OssLicenses.kt is out of step with the licensee release report: ${parts.joinToString("; ")}. " +
            "Run ./gradlew :app:androidApp:regenerateOssLicenses and commit the result."
    }

    /** [source] with its generated entries rewritten from [reportJson]; the hand-written tail is kept as it was. */
    fun regenerate(reportJson: String, source: String): String {
        val entries = written(reportJson).map { "    OssLicense(${it.arguments.joinToString(", ")})," }
        generatedSection(source) // fails loudly, before anything is written, if the markers are gone
        val head = source.substringBefore(LIST_HEADER) + LIST_HEADER + "\n"
        return head + entries.joinToString("\n") + "\n" + source.substring(source.indexOf(MANUAL_TAIL))
    }
}
