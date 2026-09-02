import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.app.shared)
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
// Versioning mirrors :app:androidApp/:core:common's git-derived scheme (see
// those files / docs/RELEASING.md item 3) but jpackage's installer formats
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
fun gitOutput(vararg args: String): String? = try {
    val result =
        providers.exec {
            commandLine(*args)
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

val gitVersionCode: Int = gitOutput("git", "rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1

val desktopPackageVersion: String = run {
    val exactTag = gitOutput("git", "describe", "--tags", "--exact-match")
    val tagPattern = Regex("""^v?([1-9]\d*)\.(\d+)\.(\d+)$""")
    val tagVersion = exactTag?.let { tagPattern.matchEntire(it) }?.let { "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}" }
    tagVersion ?: "1.0.$gitVersionCode"
}

compose.desktop {
    application {
        mainClass = "com.codingpit.muviss.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            // jlink strips any module not listed here; this exact set (not a
            // guess) was derived by running
            // `jdeps --print-module-deps --ignore-missing-deps` against the
            // built uber jar (`:app:desktopApp:packageUberJarForCurrentOS`) —
            // see docs/RELEASING.md item 8. java.sql is the one worth calling
            // out: it's what :core:database's sqlite-jdbc driver needs
            // (DatabaseFactory.jvm.kt) and is easy to forget since nothing
            // in application code imports java.sql directly.
            // jdk.httpserver is the loopback OAuth redirect server's
            // com.sun.net.httpserver (ADR 0017) — same "invisible until
            // runtime" hazard as java.sql: nothing in application code names
            // the module, and dropping it surfaces as a NoClassDefFoundError in
            // the *packaged* app only, never in a Gradle build. CI now runs
            // packageDistributionForCurrentOS on every PR so that stays caught.
            modules("java.desktop", "java.instrument", "java.management", "java.sql", "jdk.httpserver", "jdk.unsupported")

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
