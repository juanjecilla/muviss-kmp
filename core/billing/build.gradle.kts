import java.util.Properties

plugins {
    id("muviss.kmp.library")
}

// SYNC_ENTITLEMENT_OVERRIDE grants the sync entitlement without a store —
// the developer build's way in, and the only way in until a Play Console app
// and a RevenueCat product exist (ADR 0018). Absent (the default everywhere
// including CI), `NoEntitlementProvider` is bound and reports Inactive.
//
// Fail closed on purpose. The alternative — treating "no billing configured"
// as "everyone is entitled" — makes a misconfigured release give the paid
// feature away, and nothing in the build distinguishes that from intent.
// Failing closed makes the same mistake produce a visibly dead feature.
val entitlementOverride: Boolean = run {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
    (props.getProperty("SYNC_ENTITLEMENT_OVERRIDE") ?: System.getenv("SYNC_ENTITLEMENT_OVERRIDE")).toBoolean()
}

val buildConfigDir = layout.buildDirectory.dir("generated/muvissBuildConfig/commonMain/kotlin")

val generateBuildConfig by tasks.registering {
    val outDir = buildConfigDir
    val override = entitlementOverride
    inputs.property("entitlementOverride", override)
    outputs.dir(outDir)
    doLast {
        val target = outDir.get()
            .file("com/codingpit/muviss/core/billing/MuvissBuildConfig.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package com.codingpit.muviss.core.billing

            internal object MuvissBuildConfig {
                const val SYNC_ENTITLEMENT_OVERRIDE: Boolean = $override
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
                // AppClock only. `:core:sync` stays out (ADR 0018): the server's
                // record is reached through EntitlementSource, which the app shell binds.
                implementation(projects.core.common)
                implementation(libs.kotlinx.coroutinesCore)
                implementation(libs.koin.core)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
        }
    }
}
