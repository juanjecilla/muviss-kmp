import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate
import org.jetbrains.kotlin.gradle.plugin.extend

plugins {
    id("muviss.kmp.library")
}

// App version, derived from git exactly the same way :app:androidApp derives
// versionCode/versionName, and baked into a generated constant — same
// mechanism as the TMDB key (:core:network) and Sentry DSN (:app:shared), see
// ADR 0007. Lives here (rather than only in :app:androidApp's Gradle model)
// because the About screen that shows it is feature/settings/ui, a KMP module
// with no access to an Android BuildConfig/PackageManager; duplicated instead
// of shared as a buildSrc function to keep each module's Gradle file
// self-contained, consistent with the other two generators.
//
// Uses ProviderFactory.exec (not java.lang.ProcessBuilder): running an
// external process directly at configuration time is incompatible with the
// configuration cache this project enables (see gradle.properties).
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

val gitVersionName: String = run {
    val describe = gitOutput("git", "describe", "--tags", "--always", "--dirty")
    val tagPattern = Regex("""^v?(\d+\.\d+\.\d+)(-\d+-g[0-9a-f]+)?(-dirty)?$""")
    val tagVersion = describe?.let { tagPattern.matchEntire(it)?.groupValues?.get(1) }
    when {
        tagVersion != null -> tagVersion
        describe != null -> "0.1.0-dev.$gitVersionCode+$describe"
        else -> "0.1.0-dev.$gitVersionCode"
    }
}

val buildConfigDir = layout.buildDirectory.dir("generated/muvissBuildConfig/commonMain/kotlin")

val generateBuildConfig by tasks.registering {
    val outDir = buildConfigDir
    val versionName = gitVersionName
    val versionCode = gitVersionCode
    outputs.dir(outDir)
    doLast {
        val target = outDir.get()
            .file("com/codingpit/muviss/core/common/MuvissBuildConfig.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package com.codingpit.muviss.core.common

            internal object MuvissBuildConfig {
                const val APP_VERSION_NAME: String = "$versionName"
                const val APP_VERSION_CODE: Long = ${versionCode}L
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildConfig)
            dependencies {
                api(libs.kotlinx.coroutinesCore)
                api(libs.koin.core)
            }
        }
        // Sentry KMP publishes real android/jvm/iOS implementations and no-op
        // stubs for js/wasmJs. There is no default-hierarchy-template bucket
        // shared by exactly android+jvm+ios (it gives androidMain/jvmMain their
        // own top-level branches, and iosMain sits under appleMain/nativeMain),
        // so this *extends* the default template with one more group instead of
        // hand-writing `dependsOn` edges everywhere: the Kotlin Gradle plugin
        // disables the whole default template the moment it sees a manual
        // `dependsOn` on a template-managed source set (confirmed locally — it
        // warns "Default Kotlin Hierarchy Template Not Applied Correctly" and
        // silently drops iosMain from the iOS compilations), which is exactly
        // the trap #109's body flags as untried. `extend {}` is the supported
        // way to add a group without losing the rest of the tree: it creates
        // `sentryMain` (and `sentryTest`) depending on `commonMain`, with jvmMain
        // and iosMain depending on it in turn automatically.
        //
        // `androidMain` needs one manual edge on top (below): the
        // `com.android.kotlin.multiplatform.library` target this module uses
        // (see `muviss.kmp.library.gradle.kts`) does not register its source set
        // in time for `withAndroidTarget()` to attach it here, so `extend {}`
        // alone leaves `androidMain dependsOn [commonMain]` with no route to
        // `sentryMain` — confirmed by printing `sourceSets.forEach { it.name to
        // it.dependsOn }`. Unlike the manual-`dependsOn`-only approach this
        // module tried first, adding *this one* edge on top of an already
        // -`applyHierarchyTemplate`-templated tree does not trigger the
        // disablement warning above; only the fully-implicit, no-explicit-call
        // path seems to. If a future AGP/KGP upgrade makes `withAndroidTarget()`
        // attach on its own, this line becomes a no-op duplicate edge, safe to
        // delete once verified.
        //
        // Net effect: `CrashReporter`'s Sentry `beforeSend`/scrubbing code
        // (`SentryBackend.kt`, including its `platformCrashBackend()` actual)
        // lives once instead of once per platform (#109), and js/wasmJs still
        // never resolve the dependency at all.
        applyHierarchyTemplate(
            KotlinHierarchyTemplate.Templates.default.extend {
                common {
                    group("sentry") {
                        withAndroidTarget()
                        withJvm()
                        withIos()
                    }
                }
            },
        )
        sourceSets.getByName("sentryMain") {
            dependencies { implementation(libs.sentry.kotlinMultiplatform) }
        }
        androidMain.get().dependsOn(sourceSets.getByName("sentryMain"))

        // SystemLocale's web actual (`navigator.language`) lives in `webMain`,
        // the shared js+wasmJs source set the default hierarchy template
        // creates — see core/database/build.gradle.kts's matching block for
        // why `matching { }` rather than `val webMain by getting` (the
        // template creates `webMain` after this script runs).
        matching { it.name == "webMain" }.configureEach {
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }
    }
}
