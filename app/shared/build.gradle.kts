import java.util.Properties

plugins {
    id("muviss.kmp.compose")
}

// Sentry DSN is read from local.properties (gitignored) or the SENTRY_DSN env
// var and baked into a generated constant, same mechanism as the TMDB key in
// :core:network (see ADR 0007). Left blank, CrashReporter.init() no-ops.
val sentryDsn: String = run {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
    props.getProperty("SENTRY_DSN") ?: System.getenv("SENTRY_DSN") ?: ""
}

// The `environment` Sentry files an event under, so a developer's crash on a
// debug build never lands among production ones. `SENTRY_ENVIRONMENT` in
// local.properties or the environment wins; otherwise a build that asked for a
// release artefact (a `*Release*` / `bundle*` / `package*Distribution` task, or
// Xcode's `CONFIGURATION=Release` when it drives the shared framework) is
// `production`, and everything else is `development`. Guessing from task names
// is a heuristic and it is deliberately the default only — CI's release job can
// pin it explicitly — but the failure mode is benign: a mislabelled
// environment, not a lost report. Read at configuration time, which the
// configuration cache is fine with (the requested task names are part of its key).
val sentryEnvironment: String = run {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
    val explicit = (props.getProperty("SENTRY_ENVIRONMENT") ?: System.getenv("SENTRY_ENVIRONMENT"))?.takeIf { it.isNotBlank() }
    val releaseRequested = gradle.startParameter.taskNames.any { task ->
        val name = task.substringAfterLast(':')
        name.contains("release", ignoreCase = true) ||
            name.startsWith("bundle") ||
            (name.startsWith("package") && name.contains("Distribution")) ||
            name.startsWith("createReleaseDistributable")
    } || System.getenv("CONFIGURATION").equals("Release", ignoreCase = true)
    explicit ?: if (releaseRequested) "production" else "development"
}

val buildConfigDir = layout.buildDirectory.dir("generated/muvissBuildConfig/commonMain/kotlin")

val generateBuildConfig by tasks.registering {
    val outDir = buildConfigDir
    val dsn = sentryDsn
    val environment = sentryEnvironment
    outputs.dir(outDir)
    doLast {
        val target = outDir.get()
            .file("com/codingpit/muviss/MuvissBuildConfig.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package com.codingpit.muviss

            internal object MuvissBuildConfig {
                const val SENTRY_DSN: String = "$dsn"
                const val SENTRY_ENVIRONMENT: String = "$environment"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    // iOS framework consumed by the Xcode app.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildConfig)
        }
        commonMain.dependencies {
            implementation(projects.core.common)
            implementation(projects.core.designsystem)
            implementation(projects.core.network)
            implementation(projects.core.database)
            implementation(projects.core.billing)
            implementation(projects.core.sync)

            // Feature UI (screens + nav sections)
            implementation(projects.feature.search.ui)
            implementation(projects.feature.collection.ui)
            implementation(projects.feature.progress.ui)
            implementation(projects.feature.profile.ui)
            implementation(projects.feature.settings.ui)
            implementation(projects.feature.triage.ui)

            // Feature data (Koin modules)
            implementation(projects.feature.search.data)
            implementation(projects.feature.collection.data)
            implementation(projects.feature.progress.data)
            implementation(projects.feature.profile.data)
            implementation(projects.feature.settings.data)
            implementation(projects.feature.triage.data)

            implementation(libs.navigation.compose)
            implementation(libs.compose.material3.adaptiveNavigationSuite)
            implementation(libs.compose.material3.adaptive)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewmodel)
            implementation(libs.coil.compose)
            implementation(libs.coil.networkKtor)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.compose.uiToolingPreview)
        }
        // The one place the whole Koin graph exists, so the one place it can
        // be assembled and checked (see `AppGraphTest`).
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.sqldelight.sqliteDriver)
        }
    }
}
