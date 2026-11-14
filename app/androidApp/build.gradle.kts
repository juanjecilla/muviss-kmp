import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sentry)
    alias(libs.plugins.baselineprofile)
    id("muviss.version")
    id("muviss.licensee")
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
// `check` by the plugin itself. The licence classes every shipped target allows
// (Apache-2.0, MIT, BSD-3-Clause, the ConfigCat URLs) live in the `muviss.licensee`
// convention, shared with :app:desktopApp, :app:webApp and :app:shared (#615). What
// is left here is Android-only and allowed by exact coordinates. Bump the lists
// deliberately if a new licence shows up. The Settings > About > Licenses screen
// does *not* read these reports live (see feature/settings/domain's OssLicenses.kt
// for why); `regenerateOssLicenses` rewrites that file from every target's report,
// and `verifyOssLicenses` (wired into `check`, below) fails when it has fallen behind.
// -----------------------------------------------------------------------
licensee {
    // #410: Play In-App Review and the Google Play services artifacts it pulls in. Their POMs name
    // Google's Android SDK / Play Core terms by URL, not an SPDX id, so none of them is OSS. They are
    // allowed here by exact coordinates, one line each, rather than by the terms URL: the URL would
    // also admit any future artifact that quotes the same terms, and a version bump should fail this
    // build on purpose so the terms are read again before any of them moves.
    allowDependency("com.google.android.play", "review", "2.0.2") {
        because("Play In-App Review (#410): Play Core SDK terms (developer.android.com/guide/playcore/license)")
    }
    allowDependency("com.google.android.play", "review-ktx", "2.0.2") {
        because("Play In-App Review's Kotlin extensions (#410): Play Core SDK terms (developer.android.com/guide/playcore/license)")
    }
    allowDependency("com.google.android.play", "core-common", "2.0.4") {
        because("Play Core common, a dependency of Play In-App Review (#410): Play Core SDK terms (developer.android.com/guide/playcore/license)")
    }
    allowDependency("com.google.android.gms", "play-services-basement", "18.4.0") {
        because("Google Play services basement, a dependency of Play In-App Review (#410): Android SDK terms (developer.android.com/studio/terms)")
    }
    allowDependency("com.google.android.gms", "play-services-tasks", "18.2.0") {
        because("Google Play services tasks, a dependency of Play In-App Review (#410): Android SDK terms (developer.android.com/studio/terms)")
    }
}

dependencies {
    // The Play review SDK pulls androidx.fragment 1.1.0, and lint rejects that next to the
    // ActivityResult APIs `MainActivity` uses (InvalidFragmentVersionForActivityResult, needs 1.3.0).
    // A constraint raises whatever version is resolved without adding a direct dependency on it.
    constraints {
        implementation("androidx.fragment:fragment:1.9.1") {
            because("Play In-App Review (#410) pulls fragment 1.1.0; lint needs 1.3.0+ for registerForActivityResult")
        }
    }
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
    // TmdbFailureTrace: the debug-only logcat trace of swallowed TMDB failures (#177).
    implementation(projects.core.network)
    implementation(projects.core.secrets)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.androidx.work.runtimeKtx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.activity.compose)
    // Play In-App Review (#410): the store's review sheet, after a user-made Completion. Its
    // licence is the Play Core SDK terms, not an OSS one, which is why licensee below names it.
    implementation(libs.play.review.ktx)
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
// About > Licenses coverage (#562, #616, #615). OssLicenses.kt is checked in, so it can
// drift from the licensee reports as dependencies move. The list is the union of
// every shipped target's runtime graph, one licensee report per target (#615):
//   Android release  this module's variant the APK ships
//   desktop          :app:desktopApp, for every desktop OS (see its build file)
//   web JS / Wasm    :app:webApp
//   iOS              :app:shared's iosArm64 framework
// Each report's task is a dependency of both tasks below, so the reports exist
// by the time they are read; the paths are plain strings so this stays
// configuration-cache compatible.
//   verifyOssLicenses      fails, in `check`, when the union lists an artifact
//                          the list has no entry for, or the list has an entry
//                          for an artifact no report lists, or the two disagree
//                          on what an entry says.
//   regenerateOssLicenses  rewrites the generated entries from the union and
//                          keeps the hand-written tail (the bundled font).
// The decisions live in build-logic's OssLicensesList (unit-tested there). The
// task actions only read and write files, so they do not capture the build
// script (configuration cache).
// -----------------------------------------------------------------------
class OssLicenseTarget(val label: String, val task: String, val report: File)

val ossLicenseTargets = listOf(
    OssLicenseTarget(
        label = "Android release",
        task = ":app:androidApp:licenseeAndroidRelease",
        report = layout.buildDirectory.get().asFile.resolve("reports/licensee/androidRelease/artifacts.json"),
    ),
    OssLicenseTarget(
        label = "desktop",
        task = ":app:desktopApp:licensee",
        report = rootProject.file("app/desktopApp/build/reports/licensee/artifacts.json"),
    ),
    OssLicenseTarget(
        label = "web JS",
        task = ":app:webApp:licenseeJs",
        report = rootProject.file("app/webApp/build/reports/licensee/js/artifacts.json"),
    ),
    OssLicenseTarget(
        label = "web Wasm",
        task = ":app:webApp:licenseeWasmJs",
        report = rootProject.file("app/webApp/build/reports/licensee/wasmJs/artifacts.json"),
    ),
    OssLicenseTarget(
        label = "iOS",
        task = ":app:shared:licenseeIosArm64",
        report = rootProject.file("app/shared/build/reports/licensee/iosArm64/artifacts.json"),
    ),
)
val ossLicensesSource =
    rootProject.layout.projectDirectory.file("feature/settings/domain/src/commonMain/kotlin/com/codingpit/muviss/feature/settings/domain/OssLicenses.kt")

// Resolved at configuration time, so the task actions below capture plain values and not this script.
val ossLicenseReportFiles: List<Pair<String, File>> = ossLicenseTargets.map { it.label to it.report }

val verifyOssLicenses = tasks.register("verifyOssLicenses") {
    group = "verification"
    description = "Fails if About > Licenses is out of step with the licensee reports of every shipped target (#562, #616, #615)."
    ossLicenseTargets.forEach {
        dependsOn(it.task)
        inputs.file(it.report)
    }
    inputs.file(ossLicensesSource)
    val reports = ossLicenseReportFiles
    val source = ossLicensesSource.asFile
    doLast {
        val drift = OssLicensesList.drift(reports.map { (label, file) -> OssLicensesList.Report(label, file.readText()) }, source.readText())
        check(drift.isEmpty) { OssLicensesList.failureMessage(drift) }
        logger.lifecycle("About > Licenses matches the licensee reports of ${reports.size} targets, both ways")
    }
}

tasks.named("check") {
    dependsOn(verifyOssLicenses)
}

tasks.register("regenerateOssLicenses") {
    group = "help"
    description = "Rewrites feature/settings/domain's OssLicenses.kt from the licensee reports of every shipped target (#562, #615)."
    ossLicenseTargets.forEach { dependsOn(it.task) }
    val reports = ossLicenseReportFiles
    val source = ossLicensesSource.asFile
    doLast {
        source.writeText(OssLicensesList.regenerate(reports.map { (label, file) -> OssLicensesList.Report(label, file.readText()) }, source.readText()))
        logger.lifecycle("Regenerated $source from ${reports.size} licensee reports")
    }
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
// Versioning derived from git (MuvissVersion in build-logic, shared with
// :core:common and :app:desktopApp): versionCode is `major·1e6 + minor·1e4 +
// patch·100 + rcN` on an exact release tag, else the commit count on HEAD
// (ADR 0030); versionName is the latest reachable tag or a dev label.
// -----------------------------------------------------------------------
// A release build without git would ship versionCode 1, which Play rejects as a
// downgrade after the first upload (#77) — fail instead. Debug and test builds
// keep the fallback so a source-only archive still compiles.
// Only this project's release outputs count (#271). A qualified name (`:app:
// shared:linkReleaseFramework...`, `:app:desktopApp:packageReleaseDmg`) runs in
// its own project and says nothing about this one, so a shallow clone can run
// it; an unqualified one (`bundleRelease`) runs in every project that has it,
// this one included.
val isReleaseInvocation =
    gradle.startParameter.taskNames.any { name ->
        val qualified = name.contains(':')
        val ownTask = !qualified || name.removePrefix(":").startsWith(project.path.removePrefix(":") + ":")
        val local = name.substringAfterLast(':')
        ownTask && (local.contains("Release", ignoreCase = true) || local.startsWith("bundle"))
    }

// A shallow clone counts only the commits it fetched (often 1), which is the
// same downgrade with extra steps. The release formula does not read the count,
// but an untagged release build falls back to it.
if (isReleaseInvocation && muvissVersion.isShallowCheckout) {
    error("versionCode comes from git (the release tag, or `git rev-list --count HEAD`), which is wrong in a shallow clone. Use fetch-depth: 0.")
}

val gitVersionCode: Int =
    muvissVersion.codeOrNull
        ?: if (isReleaseInvocation) {
            error("versionCode comes from git (the release tag, or `git rev-list --count HEAD`), and git is unavailable. Release builds need a full git checkout (fetch-depth: 0 in CI).")
        } else {
            1
        }

val gitVersionName: String = muvissVersion.name

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
        val missing =
            ZipFile(archive).use { zip ->
                ArtifactChecks.missingComposeResources(zip.entries().asSequence().map { it.name }, entryPrefix, expected)
            }
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

// R8 must not merge the app's exceptions into other classes (#199): a merged
// exception reaches Sentry under the survivor's name. proguard-rules.pro keeps
// every `com.codingpit.muviss.**` Throwable; this canary reads the mapping and
// fails if any of a known set no longer has a class of its own. The list is
// the exceptions every Android build ships, not all of them.
// -----------------------------------------------------------------------
val exceptionCanaries = listOf(
    "com.codingpit.muviss.models.MetadataError\$NotFound",
    "com.codingpit.muviss.models.MetadataError\$Offline",
    "com.codingpit.muviss.models.MetadataError\$RateLimited",
    "com.codingpit.muviss.models.MetadataError\$Unauthorized",
    "com.codingpit.muviss.models.MetadataError\$Unknown",
    "com.codingpit.muviss.feature.settings.domain.ImportFileException",
    "com.codingpit.muviss.feature.settings.ui.MuvissTestCrash",
)

tasks.register("verifyReleaseExceptionClasses") {
    group = "verification"
    description = "Fails if R8 merged one of the app's exceptions into another class (#199)."
    dependsOn("minifyReleaseWithR8")
    // AGP 9.1+ rewrites mapping.txt after R8, merging the Compose mapping into it
    // (`mergeReleaseComposeMapping`). Reading it without that dependency is an
    // undeclared overlap Gradle refuses, and it failed the 1.1.0 RC build.
    dependsOn(tasks.matching { it.name == "mergeReleaseComposeMapping" })
    val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    // A local copy: the action must not capture the build script (configuration cache).
    val canaries = exceptionCanaries
    inputs.file(mapping)
    inputs.property("canaries", canaries)
    doLast {
        val merged = mapping.get().asFile.useLines { lines -> ArtifactChecks.mergedCanaries(lines, canaries) }
        check(merged.isEmpty()) { "R8 merged these exceptions away; Sentry would name them after another class: ${merged.joinToString()}" }
        logger.lifecycle("R8 kept all ${canaries.size} exception canaries as classes of their own")
    }
}

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

    // Android Lint (EPIC 31b #164, EPIC 25 #67). Runs over the app and every
    // module it depends on. Accessibility findings are errors and never go
    // into the baseline; lint-baseline.xml holds only what existed when lint
    // was switched on, so anything new fails the build.
    lint {
        checkDependencies = true
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        error +=
            setOf(
                "ClickableViewAccessibility",
                "ContentDescription",
                "GetContentDescriptionOverride",
                "KeyboardInaccessibleWidget",
                "LabelFor",
            )
        htmlReport = true
        sarifReport = true
    }
}
