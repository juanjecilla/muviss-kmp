import java.util.Properties

plugins {
    id("muviss.kmp.library")
}

// ConfigCat's SDK key and the compiled default for `byokEnabled`, through the
// same generated-constant mechanism as the TMDB key (:core:network) and the
// Supabase keys (:core:sync) — see ADR 0007. Both are optional: blank
// CONFIGCAT_SDK_KEY means no vendor is contacted at all and
// `remoteConfigModule` binds `DefaultsRemoteConfig`, so a clone builds and
// runs offline-clean. BYOK_ENABLED is the compiled default Remote Config
// overrides (ADR 0026). The SDK key ships in the binary, like the TMDB
// credential, so it must be a read-only key.
fun localProperty(name: String): String? {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { props.load(it) }
    return props.getProperty(name) ?: System.getenv(name)
}

val configCatSdkKey: String = localProperty("CONFIGCAT_SDK_KEY") ?: ""
val byokEnabled: Boolean = localProperty("BYOK_ENABLED").toBoolean()
// The Feature Request form (ADR 0034): blank URL keeps the Settings row hidden until the
// owner has created the form; Remote Config overrides both without a release.
val featureRequestUrl: String = localProperty("FEATURE_REQUEST_URL")?.trim() ?: ""
val featureRequestProvider: String = localProperty("FEATURE_REQUEST_PROVIDER")?.trim()?.ifEmpty { null } ?: "tally"

val buildConfigDir = layout.buildDirectory.dir("generated/muvissBuildConfig/commonMain/kotlin")

val generateBuildConfig by tasks.registering {
    val outDir = buildConfigDir
    val sdkKey = configCatSdkKey
    val byok = byokEnabled
    val requestUrl = featureRequestUrl
    val requestProvider = featureRequestProvider
    // Inputs, so changing a property regenerates the file instead of being
    // ignored until a clean build.
    inputs.property("configCatSdkKey", sdkKey)
    inputs.property("byokEnabled", byok)
    inputs.property("featureRequestUrl", requestUrl)
    inputs.property("featureRequestProvider", requestProvider)
    outputs.dir(outDir)
    doLast {
        val target = outDir.get()
            .file("com/codingpit/muviss/core/remoteconfig/MuvissBuildConfig.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package com.codingpit.muviss.core.remoteconfig

            internal object MuvissBuildConfig {
                const val CONFIGCAT_SDK_KEY: String = "$sdkKey"
                const val BYOK_ENABLED: Boolean = $byok
                const val FEATURE_REQUEST_URL: String = "$requestUrl"
                const val FEATURE_REQUEST_PROVIDER: String = "$requestProvider"
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
                api(projects.core.common)
                api(projects.core.database)
                implementation(libs.configcat.kotlinClient)
                implementation(libs.ktor.clientCore)
                implementation(libs.koin.core)
                implementation(libs.sqldelight.asyncExtensions)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqliteDriver)
            implementation(libs.ktor.clientMock)
            implementation(projects.core.testing)
        }
    }
}
