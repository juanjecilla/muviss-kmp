/**
 * The decisions behind About > Licenses coverage (#562, #616), apart from the Gradle task wiring so a test can
 * build the red case from literals. `OssLicenses.kt` is checked in and generated from licensee's release report;
 * this compares the two in both directions and rewrites the generated part. The hand-written tail below the
 * generated entries (the bundled font, which is not a Maven artifact) is outside every comparison.
 */
object OssLicensesList {
    private const val LIST_HEADER = "val ossLicenses: List<OssLicense> = listOf("
    private const val MANUAL_TAIL = "    // Not a Maven dependency"

    // Anchored at the start of a line, so an entry that is commented out does not count as listed.
    private val entryKey = Regex("""^\s*OssLicense\("((?:[^"\\]|\\.)*)", "((?:[^"\\]|\\.)*)"""", RegexOption.MULTILINE)

    /** Artifacts the list has no entry for, and entries naming an artifact the report no longer lists. Both sorted. */
    data class Drift(val missing: List<String>, val stale: List<String>) {
        val isEmpty: Boolean get() = missing.isEmpty() && stale.isEmpty()
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
    fun listedKeys(source: String): Set<String> {
        val generated = generatedSection(source)
        return entryKey.findAll(generated).map { "${it.groupValues[1]}:${it.groupValues[2]}" }.toSet()
    }

    private fun generatedSection(source: String): String {
        require(LIST_HEADER in source && MANUAL_TAIL in source) {
            "OssLicenses.kt lost its generated-list markers ($LIST_HEADER / $MANUAL_TAIL); restore them before regenerating"
        }
        return source.substring(source.indexOf(LIST_HEADER) + LIST_HEADER.length, source.indexOf(MANUAL_TAIL))
    }

    fun drift(reportJson: String, source: String): Drift {
        val reported = reportedKeys(reportJson)
        val listed = listedKeys(source)
        return Drift(
            missing = (reported - listed).sorted(),
            stale = (listed - reported).sorted(),
        )
    }

    /** The message `verifyOssLicenses` fails with: names every artifact on each side and says how to regenerate. */
    fun failureMessage(drift: Drift): String {
        val parts = buildList {
            if (drift.missing.isNotEmpty()) {
                add("no entry for ${drift.missing.size} artifact(s) in the licensee report: ${drift.missing.joinToString()}")
            }
            if (drift.stale.isNotEmpty()) {
                add("${drift.stale.size} entry(ies) for artifact(s) the licensee report no longer lists: ${drift.stale.joinToString()}")
            }
        }
        return "OssLicenses.kt is out of step with the licensee release report: ${parts.joinToString("; ")}. " +
            "Run ./gradlew :app:androidApp:regenerateOssLicenses and commit the result."
    }

    /** [source] with its generated entries rewritten from [reportJson]; the hand-written tail is kept as it was. */
    fun regenerate(reportJson: String, source: String): String {
        val entries =
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
                    val artifactName = artifact["name"] as String? ?: coordinates
                    val licenseName = license["name"] as String? ?: license["identifier"] as String? ?: url ?: "Unknown licence"
                    "    OssLicense(${kotlinString(artifact["groupId"] as String)}, ${kotlinString(artifact["artifactId"] as String)}, " +
                        "${kotlinString(artifactName)}, ${kotlinString(licenseName)}, " +
                        "${url?.let { kotlinString(it) } ?: "null"}),"
                }
        generatedSection(source) // fails loudly, before anything is written, if the markers are gone
        val head = source.substringBefore(LIST_HEADER) + LIST_HEADER + "\n"
        return head + entries.joinToString("\n") + "\n" + source.substring(source.indexOf(MANUAL_TAIL))
    }
}
