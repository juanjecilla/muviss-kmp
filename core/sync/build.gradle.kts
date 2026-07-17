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
val supabaseUrl: String = run {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
    props.getProperty("SUPABASE_URL") ?: System.getenv("SUPABASE_URL") ?: ""
}

val supabaseAnonKey: String = run {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
    props.getProperty("SUPABASE_ANON_KEY") ?: System.getenv("SUPABASE_ANON_KEY") ?: ""
}

val buildConfigDir = layout.buildDirectory.dir("generated/muvissBuildConfig/commonMain/kotlin")

val generateBuildConfig by tasks.registering {
    val outDir = buildConfigDir
    val url = supabaseUrl
    val anonKey = supabaseAnonKey
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
        }
    }
}
