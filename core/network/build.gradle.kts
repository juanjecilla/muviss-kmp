import java.util.Properties

plugins {
    id("muviss.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

// TMDB credentials are read from local.properties (gitignored) or the env and
// baked into a generated constant. Never committed. Two are supported:
//   TMDB_READ_TOKEN  the v4 read access token, sent as `Authorization: Bearer`
//                    (preferred: it never appears in a URL);
//   TMDB_API_KEY     the v3 key, sent as the `api_key` query parameter (the
//                    fallback used whenever no read token is configured).
val localProps: Properties = Properties().also { props ->
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
}

fun credential(name: String): String = localProps.getProperty(name) ?: System.getenv(name) ?: ""

val tmdbApiKey: String = credential("TMDB_API_KEY")
val tmdbReadToken: String = credential("TMDB_READ_TOKEN")

val buildConfigDir = layout.buildDirectory.dir("generated/muvissBuildConfig/commonMain/kotlin")

val generateBuildConfig by tasks.registering {
    val outDir = buildConfigDir
    val key = tmdbApiKey
    val token = tmdbReadToken
    inputs.property("tmdbApiKey", key)
    inputs.property("tmdbReadToken", token)
    outputs.dir(outDir)
    doLast {
        val target = outDir.get()
            .file("com/codingpit/muviss/core/network/MuvissBuildConfig.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package com.codingpit.muviss.core.network

            internal object MuvissBuildConfig {
                const val TMDB_API_KEY: String = "$key"
                const val TMDB_READ_TOKEN: String = "$token"
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
                implementation(libs.ktor.clientCore)
                implementation(libs.ktor.clientContentNegotiation)
                implementation(libs.ktor.serializationJson)
                implementation(libs.ktor.clientLogging)
                implementation(libs.kotlinx.serializationJson)
            }
        }
        androidMain.dependencies { implementation(libs.ktor.clientOkhttp) }
        jvmMain.dependencies { implementation(libs.ktor.clientOkhttp) }
        iosMain.dependencies { implementation(libs.ktor.clientDarwin) }
        jsMain.dependencies { implementation(libs.ktor.clientJs) }
        wasmJsMain.dependencies { implementation(libs.ktor.clientJs) }
        commonTest.dependencies {
            implementation(libs.ktor.clientMock)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
