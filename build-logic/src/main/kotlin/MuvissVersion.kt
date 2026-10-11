import org.gradle.api.provider.ProviderFactory
import java.io.File

/**
 * The app's version, derived from git so nobody has to remember to bump a
 * constant (docs/RELEASING.md item 3). One definition for every place that
 * stamps it — `:app:androidApp`'s versionCode/versionName, `:core:common`'s
 * generated `APP_VERSION_*` that the About screen shows, and `:app:desktopApp`'s
 * installer version — where there used to be three copies, one of which had
 * drifted to a different tag regex (EPIC 38, #71). Reached through the
 * `muviss.version` plugin's `muvissVersion` extension.
 *
 * Git is run with [ProviderFactory.exec], not `ProcessBuilder`: an external
 * process at configuration time is otherwise incompatible with the
 * configuration cache this project enables.
 *
 * The iOS app's `CFBundleVersion` is the one copy this cannot replace: it is a
 * bash build phase in `project.pbxproj`, which Gradle never runs.
 */
class MuvissVersion(private val providers: ProviderFactory, private val rootDir: File) {
    /** `git rev-list --count HEAD`: only ever goes up, which is all the stores require. Null without git. */
    val codeOrNull: Int? by lazy { git("rev-list", "--count", "HEAD")?.toIntOrNull() }

    /** [codeOrNull], or 1 so a source-only archive still builds. Release builds must not ship this fallback. */
    val code: Int get() = codeOrNull ?: 1

    /** A shallow clone counts only the commits it fetched (often 1), which would read as a downgrade. */
    val isShallowCheckout: Boolean by lazy { git("rev-parse", "--is-shallow-repository") == "true" }

    /**
     * The latest reachable `vX.Y.Z` tag, or a readable dev label when there is none.
     *
     * `-rcN` is dropped on purpose: production gets the RC binary itself
     * (ADR 0025), so an RC has to carry the final version name already.
     */
    val name: String by lazy {
        val describe = git("describe", "--tags", "--always", "--dirty")
        val tagVersion = describe?.let { TAG_DESCRIBE.matchEntire(it)?.groupValues?.get(1) }
        when {
            tagVersion != null -> tagVersion
            // reachable, but no tags yet
            describe != null -> "0.1.0-dev.$code+$describe"
            // git unavailable
            else -> "0.1.0-dev.$code"
        }
    }

    /**
     * What jpackage's installers accept, which is far stricter than a version
     * name: a DMG wants up to three integers and the first may not be 0, an
     * MSI up to four, a DEB anything Debian policy allows. One scheme satisfies
     * all three: an exact `vMAJOR.MINOR.PATCH` tag with MAJOR >= 1 verbatim,
     * and otherwise `1.0.<commit count>` — monotonic, and never a 0 major.
     */
    val desktopPackageVersion: String by lazy {
        val exactTag = git("describe", "--tags", "--exact-match")
        exactTag?.let { DESKTOP_TAG.matchEntire(it) }
            ?.let { "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}" }
            ?: "1.0.$code"
    }

    private fun git(vararg args: String): String? = try {
        val result =
            providers.exec {
                commandLine("git", *args)
                workingDir = rootDir
                isIgnoreExitValue = true
            }
        if (result.result.get().exitValue != 0) {
            null
        } else {
            result.standardOutput.asText.get().trim().ifEmpty { null }
        }
    } catch (_: Exception) {
        null // git not installed / not a git checkout (e.g. a source-only archive)
    }

    private companion object {
        val TAG_DESCRIBE = Regex("""^v?(\d+\.\d+\.\d+)(-rc\d+)?(-\d+-g[0-9a-f]+)?(-dirty)?$""")
        val DESKTOP_TAG = Regex("""^v?([1-9]\d*)\.(\d+)\.(\d+)$""")
    }
}
