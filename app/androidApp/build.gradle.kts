import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.licensee)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// -----------------------------------------------------------------------
// OSS license validation (EPIC 8). `com.github.jk1.dependency-license-report`
// was tried first (see docs/RELEASING.md item 6) and dropped for failing
// under the configuration cache; `app.cash.licensee` runs clean under it
// (verified: `:app:androidApp:licenseeAndroidDebug`/`licenseeAndroidRelease`
// both store a configuration-cache entry successfully) and is wired into
// `check` by the plugin itself. It fails the build if a dependency ships a
// license outside the allow-list below — bump the list deliberately if a new
// one shows up. The Settings > About > Licenses screen does *not* read this
// task's output live (see feature/settings/domain's OssLicenses.kt for why);
// re-run `licenseeAndroidRelease` and regenerate that file when dependencies
// change.
// -----------------------------------------------------------------------
licensee {
    allow("Apache-2.0")
    allow("MIT")
    allowUrl("https://opensource.org/license/mit")
}

dependencies {
    implementation(projects.app.shared)

    // EPIC 5 (new-episode notifications): the background worker talks to the
    // collection/settings features only through their `:api` modules, same
    // rule as any other cross-feature dependency (ADR 0004) — `:app:androidApp`
    // just happens to be the module that needs it this time, not a feature.
    implementation(projects.feature.collection.api)
    implementation(projects.feature.settings.api)
    // `MuvissApplication` builds the Android `DatabaseDriverFactory` itself
    // (see its doc comment) so Koin is running before any Activity exists.
    implementation(projects.core.database)
    // OAUTH_CODE_PARAM, shared with the manifest's auth-callback filter (ADR 0014).
    implementation(projects.core.sync)
    implementation(libs.koin.core)
    implementation(libs.androidx.work.runtimeKtx)

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

// -----------------------------------------------------------------------
// Release signing (see docs/RELEASING.md for the `keytool` command to
// generate a local keystore — one is never generated or committed here).
// Reads RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS /
// RELEASE_KEY_PASSWORD from local.properties (gitignored) or matching env
// vars (used by CI, see .github/workflows/release.yml). When any of them
// is missing — the common case for a fresh clone or a non-tag CI run — the
// release build type falls back to debug signing so
// `assembleRelease`/`bundleRelease` keep working for every contributor.
// -----------------------------------------------------------------------
val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

fun releaseSigningProperty(key: String): String? = localProperties.getProperty(key) ?: System.getenv(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSigningProperty("RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningProperty("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningProperty("RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningProperty("RELEASE_KEY_PASSWORD")

val hasReleaseSigningConfig =
    releaseStoreFile != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null &&
        rootProject.file(releaseStoreFile).exists()

// -----------------------------------------------------------------------
// Versioning derived from git, so nobody has to remember to bump a constant
// by hand. versionCode = total commit count on HEAD: it only ever goes up,
// which is all Play Store requires, and works even before the first tag
// exists (the repo currently has none). versionName mirrors the latest
// tag when one is reachable, otherwise falls back to a readable dev label.
// -----------------------------------------------------------------------
// Uses ProviderFactory.exec (not java.lang.ProcessBuilder) because running an
// external process directly at configuration time is incompatible with the
// configuration cache this project enables (see gradle.properties); exec()
// is the supported, cacheable equivalent.
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

        // reachable, but no tags yet
        describe != null -> "0.1.0-dev.$gitVersionCode+$describe"

        // git unavailable
        else -> "0.1.0-dev.$gitVersionCode"
    }
}

android {
    namespace = "com.codingpit.muviss"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.codingpit.muviss"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = gitVersionCode
        versionName = gitVersionName
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (hasReleaseSigningConfig) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
