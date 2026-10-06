import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.licensee)
    alias(libs.plugins.sentry)
    alias(libs.plugins.baselineprofile)
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
    // EPIC 22: `androidx.glance:glance-appwidget-external-protobuf` is
    // Glance's repackaged protobuf and the only BSD-3-Clause artifact in the
    // graph. Permissive, attribution-only, no Play Store implication — added
    // deliberately rather than by widening the list to "anything permissive".
    allow("BSD-3-Clause")
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
    implementation(projects.feature.progress.api)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(projects.models)
    // `MuvissApplication` builds the Android `DatabaseDriverFactory` itself
    // (see its doc comment) so Koin is running before any Activity exists.
    implementation(projects.core.database)
    // OAUTH_CODE_PARAM, shared with the manifest's auth-callback filter (ADR 0014).
    implementation(projects.core.sync)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.androidx.work.runtimeKtx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.activity.compose)
    // Installs the committed baseline profile on devices without Play's cloud
    // profiles (sideloads, first days after release). EPIC 34 / #77.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(projects.app.baselineprofile)

    // Glance builds its ColorProviders from the app's own M3 schemes
    // (see widget/MuvissWidget.kt), so androidApp needs material3 directly.
    implementation(compose.material3)
    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    // The widget's pure state mapper (EPIC 22). Glance renders through the
    // app-widget host rather than Skiko, so its composition is not reachable
    // from `runComposeUiTest`; what it decides before composing is.
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutinesTest)
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

// A release build without git would ship versionCode 1, which Play rejects as a
// downgrade after the first upload (#77) — fail instead. Debug and test builds
// keep the fallback so a source-only archive still compiles.
val isReleaseInvocation =
    gradle.startParameter.taskNames.any { name ->
        name.contains("Release", ignoreCase = true) || name.substringAfterLast(':').startsWith("bundle")
    }

// A shallow clone counts only the commits it fetched (often 1), which is the
// same downgrade with extra steps.
val isShallowCheckout = gitOutput("git", "rev-parse", "--is-shallow-repository") == "true"

if (isReleaseInvocation && isShallowCheckout) {
    error("versionCode comes from `git rev-list --count HEAD`, which is wrong in a shallow clone. Use fetch-depth: 0.")
}

val gitVersionCode: Int =
    gitOutput("git", "rev-list", "--count", "HEAD")?.toIntOrNull()
        ?: if (isReleaseInvocation) {
            error("versionCode comes from `git rev-list --count HEAD`, and git is unavailable. Release builds need a full git checkout (fetch-depth: 0 in CI).")
        } else {
            1
        }

val gitVersionName: String = run {
    val describe = gitOutput("git", "describe", "--tags", "--always", "--dirty")
    // `-rcN` is dropped on purpose: production gets the RC binary itself
    // (ADR 0025), so an RC has to carry the final versionName already.
    val tagPattern = Regex("""^v?(\d+\.\d+\.\d+)(-rc\d+)?(-\d+-g[0-9a-f]+)?(-dirty)?$""")
    val tagVersion = describe?.let { tagPattern.matchEntire(it)?.groupValues?.get(1) }
    when {
        tagVersion != null -> tagVersion

        // reachable, but no tags yet
        describe != null -> "0.1.0-dev.$gitVersionCode+$describe"

        // git unavailable
        else -> "0.1.0-dev.$gitVersionCode"
    }
}

// -----------------------------------------------------------------------
// Compose Resources must reach the package (#165). They ride on Android
// resources, which `com.android.kotlin.multiplatform.library` skips unless
// `muviss.kmp.compose` enables them — and when it did not, every string and
// the bundled font were silently left out: JVM tests and goldens read them off
// the classpath, both assemble tasks succeed, and the app threw
// `MissingResourceException` on launch. So read the built archive instead and
// fail unless every module that owns `composeResources` is in it. CI runs the
// APK check after `assembleDebug`; the release workflow runs the bundle check
// after `bundleRelease`.
// -----------------------------------------------------------------------
val composeResourcePackages: List<String> =
    rootProject.subprojects
        .filter { it.file("src/commonMain/composeResources").isDirectory }
        // Same derivation as `packageOfResClass` in muviss.kmp.compose.
        .map { "com.codingpit.muviss." + it.path.removePrefix(":").replace(":", ".").replace("-", "") + ".generated.resources" }
        .sorted()

fun registerComposeResourcesCheck(
    name: String,
    archiveTask: String,
    archiveDir: String,
    extension: String,
    entryPrefix: String,
) = tasks.register(name) {
    group = "verification"
    description = "Fails if the $extension built by $archiveTask is missing any module's Compose Resources (#165)."
    dependsOn(archiveTask)
    val dir = layout.buildDirectory.dir(archiveDir)
    val expected = composeResourcePackages
    inputs.dir(dir)
    inputs.property("expected", expected)
    doLast {
        val archive =
            dir.get().asFile.listFiles { f -> f.extension == extension }?.singleOrNull()
                ?: error("Expected exactly one .$extension in ${dir.get().asFile}")
        val present =
            ZipFile(archive).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith(entryPrefix) }
                    .map { it.removePrefix(entryPrefix).substringBefore('/') }
                    .toSet()
            }
        val missing = expected - present
        check(missing.isEmpty()) {
            "${archive.name} has no Compose Resources for: ${missing.joinToString()}. " +
                "Is `androidResources.enable = true` still set in muviss.kmp.compose?"
        }
        logger.lifecycle("${archive.name}: Compose Resources present for ${expected.size} modules")
    }
}

registerComposeResourcesCheck(
    name = "verifyDebugApkComposeResources",
    archiveTask = "assembleDebug",
    archiveDir = "outputs/apk/debug",
    extension = "apk",
    entryPrefix = "assets/composeResources/",
)
registerComposeResourcesCheck(
    name = "verifyReleaseBundleComposeResources",
    archiveTask = "bundleRelease",
    archiveDir = "outputs/bundle/release",
    extension = "aab",
    entryPrefix = "base/assets/composeResources/",
)

// -----------------------------------------------------------------------
// Sentry Gradle plugin (EPIC 26). Release builds are minified, so without the R8
// mapping every production stack trace is obfuscated. The plugin uploads it and
// stamps the build so Sentry can pair the two: `release` is
// `<applicationId>@<versionName>+<versionCode>` and `dist` is the versionCode —
// the same strings `MuvissCrashReporting.config()` hands the SDK at runtime, and
// they have to stay identical or a trace cannot find its mapping.
//
// Off unless `SENTRY_AUTH_TOKEN` is present (env or local.properties), the same
// posture as the DSN and the signing keys: a fresh clone, a contributor and CI's
// per-PR builds have none and must still build. With no token the plugin does
// nothing at all — no upload, no network. It is set for release builds only.
//
// `autoInstallation` is off because `sentry-kotlin-multiplatform` already brings
// `sentry-android`; letting the plugin add its own pin would fight the version the
// KMP release was built against (see `sentryKmp`/`sentryCocoa` in the catalog).
// Tracing instrumentation is off too: this app does not use Sentry performance,
// and bytecode-instrumenting OkHttp/Compose/file IO on every build for a feature
// nobody reads costs build time and risks R8.
// -----------------------------------------------------------------------
val sentryAuthToken: String? = localProperties.getProperty("SENTRY_AUTH_TOKEN") ?: System.getenv("SENTRY_AUTH_TOKEN")?.takeIf { it.isNotBlank() }
val sentryOrg: String? = localProperties.getProperty("SENTRY_ORG") ?: System.getenv("SENTRY_ORG")?.takeIf { it.isNotBlank() }
val sentryProject: String? = localProperties.getProperty("SENTRY_PROJECT") ?: System.getenv("SENTRY_PROJECT")?.takeIf { it.isNotBlank() }
val uploadMapping = sentryAuthToken != null && sentryOrg != null && sentryProject != null

sentry {
    org.set(sentryOrg)
    projectName.set(sentryProject)
    authToken.set(sentryAuthToken)
    includeProguardMapping.set(uploadMapping)
    autoUploadProguardMapping.set(uploadMapping)
    includeDependenciesReport.set(false)
    includeSourceContext.set(false)
    uploadNativeSymbols.set(false)
    telemetry.set(false)
    autoInstallation.enabled.set(false)
    tracingInstrumentation.enabled.set(false)
    ignoredBuildTypes.set(setOf("debug"))
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
