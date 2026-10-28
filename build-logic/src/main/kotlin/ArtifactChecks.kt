/**
 * The decisions behind the Android artifact checks (`verifyReleaseExceptionClasses`,
 * `verifyDebugApkComposeResources`, `verifyReleaseBundleComposeResources`), apart from the Gradle task
 * wiring so a test can build the red case from literals (#490). A check nobody has seen go red reads as
 * protection and gives none; these are the two that were each added after a real incident.
 */
object ArtifactChecks {

    /** The classes an R8 `mapping.txt` keeps: its unindented `original -> obfuscated:` lines, by original name. */
    fun classesIn(mappingLines: Sequence<String>): Set<String> = mappingLines
        .filter { !it.startsWith(" ") && it.contains(" -> ") }
        .map { it.substringBefore(" -> ") }
        .toSet()

    /** The [canaries] R8 merged into another class, so Sentry would name them after the survivor (#199). */
    fun mergedCanaries(mappingLines: Sequence<String>, canaries: List<String>): List<String> {
        val classes = classesIn(mappingLines)
        return canaries.filterNot { it in classes }
    }

    /** The modules in [expected] that have no Compose Resources under [entryPrefix] in an archive with [entryNames] (#165). */
    fun missingComposeResources(entryNames: Sequence<String>, entryPrefix: String, expected: List<String>): Set<String> {
        val present = entryNames
            .filter { it.startsWith(entryPrefix) }
            .map { it.removePrefix(entryPrefix).substringBefore('/') }
            .toSet()
        return expected.toSet() - present
    }
}
