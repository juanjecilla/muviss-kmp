import java.util.Properties

plugins {
    id("muviss.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

// Supabase project keys, same generated-constant mechanism as the TMDB key
// (:core:network) and the Sentry DSN (:app:shared) — see ADR 0007. Left
// blank (the default for local/dev builds and CI, which never see these),
// sync stays entirely disabled: `syncModule` binds a `NoOpSyncBackend`
// instead of `SupabaseSyncBackend` (see ADR 0009 and docs/SYNC.md).
//
// SYNC_ENABLED is a third, deliberately separate switch (ADR 0018). Missing
// keys already hide sync, but that gate is an *omission* — nothing
// distinguishes "we chose not to ship this" from "someone forgot a
// property", and anyone adding keys to debug a build would silently turn on
// a paid feature. SYNC_ENABLED is the statement of intent, and it is the one
// switch that also hides the paywall. Release CI sets none of the three.
fun localProperty(name: String): String? {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
    return props.getProperty(name) ?: System.getenv(name)
}

val supabaseUrl: String = localProperty("SUPABASE_URL") ?: ""
val supabaseAnonKey: String = localProperty("SUPABASE_ANON_KEY") ?: ""
val syncEnabled: Boolean = localProperty("SYNC_ENABLED").toBoolean()

val buildConfigDir = layout.buildDirectory.dir("generated/muvissBuildConfig/commonMain/kotlin")

val generateBuildConfig by tasks.registering {
    val outDir = buildConfigDir
    val url = supabaseUrl
    val anonKey = supabaseAnonKey
    val enabled = syncEnabled
    // Declared as inputs so changing a property regenerates the file. Without
    // these the task is up-to-date on its output alone and a swapped key is
    // silently ignored until a clean build (:core:network gets this right;
    // this module did not).
    inputs.property("supabaseUrl", url)
    inputs.property("supabaseAnonKey", anonKey)
    inputs.property("syncEnabled", enabled)
    outputs.dir(outDir)
    doLast {
        val target = outDir.get()
            .file("com/codingpit/muviss/core/sync/MuvissBuildConfig.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package com.codingpit.muviss.core.sync

            internal object MuvissBuildConfig {
                const val SUPABASE_URL: String = "$url"
                const val SUPABASE_ANON_KEY: String = "$anonKey"
                const val SYNC_ENABLED: Boolean = $enabled
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
                api(projects.models)
                api(projects.core.common)
                api(projects.core.database)
                implementation(projects.core.network)
                implementation(libs.ktor.clientCore)
                implementation(libs.ktor.clientContentNegotiation)
                implementation(libs.ktor.serializationJson)
                implementation(libs.ktor.clientLogging)
                implementation(libs.kotlinx.serializationJson)
                implementation(libs.koin.core)
                implementation(libs.sqldelight.coroutinesExtensions)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqliteDriver)
            // Drives SupabaseSyncBackend without a live project: token refresh
            // and HTTP-status handling are only observable against real
            // responses, and ADR 0009 rules out network calls in tests.
            implementation(libs.ktor.clientMock)
            // `FakeSupabaseServer` (the wire-level Supabase stand-in the sync
            // suites run the real backend and engine against) and
            // `CountingDriver` (the operation-count budgets).
            implementation(projects.core.testing)
            // Asserts the status-derivation invariant a pull must not break.
            implementation(projects.core.model)
        }
    }
}
