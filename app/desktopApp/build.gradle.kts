import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("muviss.version")
}

// The tray icon (EPIC 23) is the app icon. Rather than keep a second copy of the
// same PNG under src/main/resources, put `icons/` on the runtime classpath —
// filtered to the PNG, since the .icns/.ico are jpackage inputs only and have no
// business in the jar.
sourceSets.named("main") {
    resources.srcDir(layout.projectDirectory.dir("icons"))
    resources.include("**/*.png")
}

dependencies {
    implementation(projects.app.shared)
    // Named for the same reason as :core:sync below — :app:shared depends on
    // these with `implementation`, and DesktopEpisodeRefresh talks to all three.
    implementation(projects.feature.collection.api)
    implementation(projects.feature.progress.api)
    implementation(projects.feature.settings.api)
    implementation(projects.models)
    // :app:shared depends on these with `implementation`, so nothing leaks
    // transitively. Named here because this module starts Koin itself and binds
    // the loopback OAuth server into the graph (ADR 0017).
    implementation(projects.core.sync)
    implementation(projects.core.database)
    implementation(libs.koin.core)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutinesTest)
}

// -----------------------------------------------------------------------
// EPIC 12: native installers (DMG/MSI/DEB) via Compose's jpackage wrapper.
//
// Versioning comes from the same git-derived scheme as :app:androidApp and
// :core:common (MuvissVersion in build-logic, docs/RELEASING.md item 3), but jpackage's installer formats
// are far stricter about *syntax* than an Android versionName:
//  - DMG (macOS, pkgbuild under the hood): parses as up to 3 dot-separated
//    integers, and the FIRST one can't be 0 — a "0.x.y" packageVersion (what
//    every untagged build on this repo currently has, e.g. "0.1.0-dev.12")
//    makes `packageDmg` fail outright with "Invalid Package-Version".
//  - MSI (Windows, WiX under the hood): up to 4 dot-separated integers.
//  - DEB (Linux): Debian policy version syntax — the most permissive of the
//    three, no reason to diverge from the other two though.
// Rather than maintain three different mappings, one safe scheme covers all
// three: a real `vMAJOR.MINOR.PATCH` tag (MAJOR >= 1) is used verbatim, and
// every untagged/dev build (the norm today — this repo has no tags yet)
// falls back to "1.0.<commitCount>" — monotonic (commit count only grows),
// always satisfies every format's "major can't be 0" rule, and needs no
// dev-suffix stripping since it never had one.
// -----------------------------------------------------------------------
val desktopPackageVersion: String = muvissVersion.desktopPackageVersion

// -----------------------------------------------------------------------
// The jlink module list, and the check that keeps it honest (issue #43).
//
// jlink strips every JDK module not reachable from this set of roots, and a
// module that is genuinely needed and simply absent is a runtime
// `NoClassDefFoundError` in the *packaged* app — never a build failure. jlink
// only fails on a module *name* it cannot resolve, so the existing
// `packageDistributionForCurrentOS` step in CI catches a typo and nothing
// else, and the packaged smoke launch only exercises the startup path
// (`jdk.httpserver` is not touched until a user clicks sign in).
//
// So the list is verified rather than remembered: `verifyJlinkModules` below
// re-derives what the app actually needs with `jdeps` and fails if this
// declaration cannot supply it.
//
// `java.prefs` was added here by that check on its first run. It is what
// `DesktopWindowState.kt` stores the window geometry in, and it was never
// declared — it survived only because `java.desktop requires java.prefs`, so
// jlink pulled it in transitively. That worked, but it meant window
// persistence depended on an implementation detail of an unrelated root.
// It is a direct dependency of our own code, so it is a root.
val jlinkModules = listOf(
    "java.desktop",
    "java.instrument",
    "java.management",
    // Window geometry (`java.util.prefs`, DesktopWindowState.kt).
    "java.prefs",
    // :core:database's sqlite-jdbc driver (DatabaseFactory.jvm.kt). Nothing in
    // application code imports java.sql directly, which is what makes it easy
    // to forget.
    "java.sql",
    // The loopback OAuth redirect server's com.sun.net.httpserver (ADR 0017).
    "jdk.httpserver",
    "jdk.unsupported",
)

/**
 * Re-derives the JDK modules the desktop app needs and checks [jlinkModules]
 * can supply them.
 *
 * Two external calls, because the question has two halves:
 *
 *  - `jdeps --print-module-deps` over the uber jar says what is **required**.
 *  - `java --limit-modules <roots> --list-modules` says what those roots
 *    **resolve to**, which is what jlink will actually put in the image. The
 *    difference matters: `java.prefs` is required and is not a root, but
 *    `java.desktop` requires it, so it is present anyway. Asserting
 *    `declared ⊇ required` would report a failure that is not one.
 *
 * Missing is fatal. An "extra" root — declared but never named by jdeps — is
 * only reported, because jdeps sees static references and cannot see a module
 * reached by reflection or JNI; such a root looks exactly like a mistake and
 * removing it would be one.
 *
 * A required module that resolves only transitively is reported too. Nothing
 * is broken while it does, but it is load-bearing by accident: it disappears
 * the moment whichever root happens to require it is dropped.
 */
@CacheableTask
abstract class VerifyJlinkModules : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val uberJar: RegularFileProperty

    @get:Input
    abstract val declaredModules: ListProperty<String>

    /** The JDK whose `jdeps` and `java` are used; an @Input so a JDK change re-runs this. */
    @get:Input
    abstract val javaHome: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun verify() {
        val bin = File(javaHome.get(), "bin")
        val declared = declaredModules.get().toSortedSet()

        val required = run(File(bin, "jdeps"), "--print-module-deps", "--ignore-missing-deps", uberJar.get().asFile.absolutePath)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()

        val resolved = run(File(bin, "java"), "--limit-modules", declared.joinToString(","), "--list-modules")
            .lineSequence()
            .map { it.substringBefore("@").trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()

        val missing = required - resolved
        // java.base is mandated in every image and is never declared as a root
        // anywhere, so it is not interesting in either direction.
        val transitiveOnly = (required - declared).intersect(resolved) - "java.base"
        val unused = declared - required

        val lines = buildList {
            add("declared roots : ${declared.joinToString(", ")}")
            add("required (jdeps): ${required.joinToString(", ")}")
            add("resolved image  : ${resolved.joinToString(", ")}")
            if (transitiveOnly.isNotEmpty()) add("required but only transitive: ${transitiveOnly.joinToString(", ")}")
            if (unused.isNotEmpty()) add("declared but not required by jdeps: ${unused.joinToString(", ")}")
            add(if (missing.isEmpty()) "OK" else "MISSING: ${missing.joinToString(", ")}")
        }
        report.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))

        transitiveOnly.forEach {
            logger.warn("[jlink] '$it' is required but is not a declared root; it survives only because another root requires it.")
        }
        unused.forEach {
            logger.warn("[jlink] '$it' is declared but jdeps does not name it. Fine if it is reached by reflection or JNI; otherwise it is dead weight.")
        }

        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("The jlink module list cannot supply what the app needs.")
                    appendLine("Missing from the resolved runtime image: ${missing.joinToString(", ")}")
                    appendLine()
                    appendLine("Add them to `jlinkModules` in app/desktopApp/build.gradle.kts.")
                    appendLine("Leaving them out is not a build failure — it is a NoClassDefFoundError")
                    appendLine("in the packaged app, at whatever moment the user first reaches that code.")
                },
            )
        }
    }

    private fun run(tool: File, vararg args: String): String {
        val out = ByteArrayOutputStream()
        execOps.exec {
            commandLine(tool.absolutePath, *args)
            standardOutput = out
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

// Inside `afterEvaluate` because the Compose plugin registers its packaging
// tasks in one of its own — `packageUberJarForCurrentOS` does not exist while
// this script is being evaluated, and `tasks.named` on it fails outright.
// Callbacks run in registration order and the plugin's was registered when it
// was applied at the top of this file, so by the time this runs the task is
// there. `named`/`map` keep it lazy: configuring the project still builds
// nothing.
afterEvaluate {
    val uberJarTask = tasks.named("packageUberJarForCurrentOS")

    val verifyJlinkModules = tasks.register<VerifyJlinkModules>("verifyJlinkModules") {
        group = "verification"
        description = "Re-derives the JDK modules the app needs with jdeps and checks the jlink module list can supply them."
        uberJar.set(layout.file(uberJarTask.map { it.outputs.files.single { f -> f.name.endsWith(".jar") } }))
        declaredModules.set(jlinkModules)
        javaHome.set(providers.systemProperty("java.home"))
        report.set(layout.buildDirectory.file("reports/jlink-modules.txt"))
    }

    tasks.named("check") { dependsOn(verifyJlinkModules) }
}

compose.desktop {
    application {
        mainClass = "com.codingpit.muviss.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            // The list itself is `jlinkModules` above, so `verifyJlinkModules`
            // checks the same declaration this passes to jlink rather than a
            // second copy of it that could drift.
            modules(*jlinkModules.toTypedArray())

            packageName = "Muviss"
            packageVersion = desktopPackageVersion
            vendor = "Juanje Cilla"
            description = "A personal, offline-first tracker for movies and TV shows."
            copyright = "Copyright © 2026 Juanje Cilla"
            licenseFile.set(rootProject.file("LICENSE"))

            macOS {
                iconFile.set(project.file("icons/icon.icns"))
                bundleID = "com.codingpit.muviss"
            }
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "Muviss"
                perUserInstall = true
                shortcut = true
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
            }
        }
    }
}
