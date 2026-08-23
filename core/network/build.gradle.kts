import java.util.Properties

plugins {
    id("muviss.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

// TMDB key is read from local.properties (gitignored) or the TMDB_API_KEY env
// var and baked into a generated constant. Never committed.
val tmdbApiKey: String = run {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
    props.getProperty("TMDB_API_KEY") ?: System.getenv("TMDB_API_KEY") ?: ""
}

val buildConfigDir = layout.buildDirectory.dir("generated/muvissBuildConfig/commonMain/kotlin")

val generateBuildConfig by tasks.registering {
    val outDir = buildConfigDir
    val key = tmdbApiKey
    inputs.property("tmdbApiKey", key)
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
