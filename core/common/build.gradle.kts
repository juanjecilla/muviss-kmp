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
        // stubs for js/wasmJs. We scope the dependency to the targets that get
        // a real implementation ourselves (see CrashReporter's actuals) so the
        // web targets never resolve it at all.
        androidMain.dependencies { implementation(libs.sentry.kotlinMultiplatform) }
        iosMain.dependencies { implementation(libs.sentry.kotlinMultiplatform) }
        jvmMain.dependencies { implementation(libs.sentry.kotlinMultiplatform) }

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
