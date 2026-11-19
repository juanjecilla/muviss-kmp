/**
 * The decisions behind About > Licenses coverage (#562, #616, #666, #615), apart from the Gradle task wiring so a test can
 * build the red case from literals. `OssLicenses.kt` is checked in and generated from licensee's reports, one per shipped
 * target (#615): this unions them, compares the union with the list in both directions, compares what each entry says, and
 * rewrites the generated part. The hand-written tail below the generated entries (the bundled font, which is not a Maven
 * artifact) is outside every comparison.
 */
object OssLicensesList {
    private const val LIST_HEADER = "val ossLicenses: List<OssLicense> = listOf("
    private const val MANUAL_TAIL = "    // Not a Maven dependency"

    /** An artifact may carry at most this many licence records on the screen (#615). */
    const val MAX_LICENCES = 2

    /** One licensee report, named by the target it came from so a failure can say where a missing artifact is from. */
    data class Report(val label: String, val json: String)

    // The arguments of an entry, each as Kotlin source (a quoted literal, or `null` for a URL). The first five are the
    // artifact and its first licence; the optional last two are its second licence (#615), written only when present.
    // Anchored at the start of a line, so an entry that is commented out does not count as listed.
    private val entryRegex = Regex(
        """^\s*OssLicense\(("(?:[^"\\]|\\.)*"),\s*("(?:[^"\\]|\\.)*"),\s*("(?:[^"\\]|\\.)*"),\s*("(?:[^"\\]|\\.)*"),\s*("(?:[^"\\]|\\.)*"|null)(?:,\s*("(?:[^"\\]|\\.)*"),\s*("(?:[^"\\]|\\.)*"|null))?\)""",
        RegexOption.MULTILINE,
    )
    // An entry inside /* ... */ is not listed either, any more than one behind `//` (which `^\s*` already skips).
    private val blockComment = Regex("""/\*[\s\S]*?\*/""")

    /** One licence record as the screen shows it. */
    private data class Licence(val name: String, val url: String?)

    /**
     * One entry as it appears in the file: the arguments as written, Kotlin source text, so [key] is the coordinates as
     * the file spells them and [arguments] compare exactly against what the generator writes.
     */
    private class Listed(val arguments: List<String>) {
        val key: String get() = "${arguments[0].removeSurrounding("\"")}:${arguments[1].removeSurrounding("\"")}"
    }

    /** One artifact as the union sees it: its coordinates, its name and licences, and the reports that list it. */
    private data class Written(
        val group: String,
        val artifact: String,
        val name: String,
        val licences: List<Licence>,
        val sources: List<String>,
    ) {
        val key: String get() = "$group:$artifact"

        /** The arguments the generator writes for this artifact, exactly as they appear inside `OssLicense(...)`. */
        val arguments: List<String>
            get() = buildList {
                add(kotlinString(group))
                add(kotlinString(artifact))
                add(kotlinString(name))
                licences.forEach { licence ->
                    add(kotlinString(licence.name))
                    add(licence.url?.let { kotlinString(it) } ?: "null")
                }
            }
    }

    /**
     * Artifacts the list has no entry for, entries naming an artifact no report lists, artifacts listed twice, and
     * entries whose name, licences or licence URLs differ from the union. All sorted. [sources] maps each artifact in
     * the union to the labels of the reports that list it, so [failureMessage] can say where a missing one is from.
     */
    data class Drift(
        val missing: List<String>,
        val stale: List<String>,
        val duplicated: List<String> = emptyList(),
        val changed: List<String> = emptyList(),
        val sources: Map<String, List<String>> = emptyMap(),
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

    /** `group:artifact` for every artifact in one licensee `artifacts.json`. */
    fun reportedKeys(reportJson: String): Set<String> =
        artifacts(reportJson).map { "${it["groupId"]}:${it["artifactId"]}" }.toSet()

    /** The generated section of [source] (between the list header and the hand-written tail), by `group:artifact`. */
    fun listedKeys(source: String): Set<String> = listedEntries(source).map { it.key }.toSet()

    // In order and with repeats: a set would hide a coordinate listed twice, which the Licenses screen's lazy
    // list cannot show (both rows would share one key).
    private fun listedEntries(source: String): List<Listed> =
        entryRegex.findAll(generatedSection(source).replace(blockComment, "")).map { match ->
            Listed(match.groups.drop(1).mapNotNull { it?.value })
        }.toList()

    private fun generatedSection(source: String): String {
        require(LIST_HEADER in source && MANUAL_TAIL in source) {
            "OssLicenses.kt lost its generated-list markers ($LIST_HEADER / $MANUAL_TAIL); restore them before regenerating"
        }
        return source.substring(source.indexOf(LIST_HEADER) + LIST_HEADER.length, source.indexOf(MANUAL_TAIL))
    }

    /** What one report says about each of its artifacts, before the reports are unioned. */
    private fun perReport(report: Report): List<Written> =
        artifacts(report.json)
            .sortedWith(compareBy({ it["groupId"] as String }, { it["artifactId"] as String }))
            .map { artifact ->
                // licensee can report both collections for one artifact; count them together.
                val records = ((artifact["spdxLicenses"] as? List<*>).orEmpty()) + ((artifact["unknownLicenses"] as? List<*>).orEmpty())
                val coordinates = "${artifact["groupId"]}:${artifact["artifactId"]}"
                require(records.isNotEmpty()) { "$coordinates has no licence in the ${report.label} report" }
                Written(
                    group = artifact["groupId"] as String,
                    artifact = artifact["artifactId"] as String,
                    // licensee allows both names to be null: fall back to the coordinates and the licence's id or URL.
                    name = artifact["name"] as String? ?: coordinates,
                    licences = records.map { record ->
                        val license = record as Map<*, *>
                        val url = license["url"] as String?
                        Licence(
                            name = license["name"] as String? ?: license["identifier"] as String? ?: url ?: "Unknown licence",
                            url = url,
                        )
                    }.distinct(),
                    sources = listOf(report.label),
                )
            }

    /**
     * The union of [reports], one entry per artifact, sorted by coordinates. An artifact two targets ship under the same
     * coordinates is one entry: its licences are the distinct records of both, and it is refused when they disagree on
     * its name (the screen shows one name) or when it ends up with more than [MAX_LICENCES] licences.
     */
    private fun union(reports: List<Report>): List<Written> {
        require(reports.isNotEmpty()) { "no licensee reports to compare the list with" }
        val merged = linkedMapOf<String, Written>()
        reports.flatMap { perReport(it) }.forEach { item ->
            val existing = merged[item.key]
            if (existing == null) {
                merged[item.key] = item
                return@forEach
            }
            val sources = (existing.sources + item.sources).distinct()
            require(existing.name == item.name) {
                "${item.key} is named \"${existing.name}\" in ${existing.sources.joinToString()} but \"${item.name}\" in " +
                    "${item.sources.joinToString()}; the screen shows one name, so decide which one it is"
            }
            merged[item.key] = existing.copy(licences = (existing.licences + item.licences).distinct(), sources = sources)
        }
        return merged.values.sortedWith(compareBy({ it.group }, { it.artifact })).onEach { written ->
            require(written.licences.size <= MAX_LICENCES) {
                "${written.key} has ${written.licences.size} licences across ${written.sources.joinToString()} " +
                    "(at most $MAX_LICENCES are shown); decide how to show it"
            }
        }
    }

    /** [Listed] entries whose arguments differ from what the union writes for the same coordinates. */
    fun drift(reports: List<Report>, source: String): Drift {
        val union = union(reports)
        val unionByKey = union.associateBy { it.key }
        val entries = listedEntries(source)
        val listed = entries.map { it.key }.toSet()
        return Drift(
            missing = (unionByKey.keys - listed).sorted(),
            stale = (listed - unionByKey.keys).sorted(),
            duplicated = entries.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys.sorted(),
            changed = entries
                .filter { entry -> unionByKey[entry.key]?.let { it.arguments != entry.arguments } == true }
                .map { it.key }
                .distinct()
                .sorted(),
            sources = union.associate { it.key to it.sources },
        )
    }

    /** One-report form of [drift], for a single licensee `artifacts.json`. */
    fun drift(reportJson: String, source: String): Drift = drift(listOf(Report("licensee report", reportJson)), source)

    /** The message `verifyOssLicenses` fails with: names every artifact on each side, and where a missing one is from. */
    fun failureMessage(drift: Drift): String {
        fun named(key: String): String = drift.sources[key]?.let { "$key (${it.joinToString()})" } ?: key
        val parts = buildList {
            if (drift.missing.isNotEmpty()) {
                add("the list has no entry for ${drift.missing.size} artifact(s) the licensee reports list: ${drift.missing.joinToString { named(it) }}")
            }
            if (drift.stale.isNotEmpty()) {
                add("${drift.stale.size} entry(ies) for artifact(s) no licensee report lists any more: ${drift.stale.joinToString()}")
            }
            if (drift.duplicated.isNotEmpty()) {
                add("${drift.duplicated.size} artifact(s) listed more than once: ${drift.duplicated.joinToString()}")
            }
            if (drift.changed.isNotEmpty()) {
                add("${drift.changed.size} entry(ies) whose name, licence or licence URL differs from the licensee reports: ${drift.changed.joinToString()}")
            }
        }
        return "OssLicenses.kt is out of step with the licensee reports: ${parts.joinToString("; ")}. " +
            "Run ./gradlew :app:androidApp:regenerateOssLicenses and commit the result."
    }

    /** [source] with its generated entries rewritten from the union of [reports]; the hand-written tail is kept as it was. */
    fun regenerate(reports: List<Report>, source: String): String {
        val entries = union(reports).map { "    OssLicense(${it.arguments.joinToString(", ")})," }
        generatedSection(source) // fails loudly, before anything is written, if the markers are gone
        val head = source.substringBefore(LIST_HEADER) + LIST_HEADER + "\n"
        return head + entries.joinToString("\n") + "\n" + source.substring(source.indexOf(MANUAL_TAIL))
    }

    /** One-report form of [regenerate], for a single licensee `artifacts.json`. */
    fun regenerate(reportJson: String, source: String): String = regenerate(listOf(Report("licensee report", reportJson)), source)
}
