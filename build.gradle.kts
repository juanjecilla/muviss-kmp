import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeOutputKind

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

// Captured in root scope where the `libs` accessor is available.
val ktlintVersion = libs.versions.ktlint.get()
val detektComposeRules = libs.detekt.composeRules

// Rules relaxed for this codebase (platform entrypoints named main.kt, PascalCase
// @Composable functions, expect/actual files named after their common declaration).
val ktlintOverrides = mapOf(
    "ktlint_standard_filename" to "disabled",
    "ktlint_standard_function-naming" to "disabled",
    "ktlint_standard_max-line-length" to "disabled",
)

// Formatting (Spotless + ktlint) and static analysis (Detekt) on every module.
allprojects {
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            targetExclude("**/build/**")
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
    }

    configure<DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        basePath = rootProject.projectDir.absolutePath
    }

    dependencies {
        add("detektPlugins", detektComposeRules)
    }

    // Analyse all Kotlin sources (multiplatform-friendly) rather than the JVM-only default.
    tasks.withType<Detekt>().configureEach {
        setSource(files(projectDir.resolve("src")))
        include("**/*.kt")
        exclude("**/build/**")
    }

    linkSentryCocoaFramework()
}

// --- Sentry's iOS SDK, for Apple link steps ---------------------------------
//
// `io.sentry:sentry-kotlin-multiplatform`'s iOS artifacts are cinterop klibs
// over Sentry's Objective-C SDK; the cinterop manifest carries
// `linkerOpts=-framework Sentry`, so the framework itself has to be on the
// linker's search path or every Apple *executable* link fails with
// `ld: framework 'Sentry' not found`. Frameworks that Xcode links (the iOS app)
// could get it from Swift Package Manager, but the `linkDebugTest*` /
// `linkReleaseTest*` binaries `./gradlew build` produces are linked by Gradle
// with no Xcode in the picture, which is why this is wired here rather than in
// `app/iosApp`. The zip resolves through the `ivy` repository declared in
// `settings.gradle.kts`.
//
// `Sentry-Dynamic.xcframework`, not the static `Sentry.xcframework`: the static
// build carries Swift code, and linking it pulls in Swift's static
// compatibility shims (`__swift_FORCE_LOAD_$_swiftCompatibility56` and friends),
// which Kotlin/Native's link step has no Swift toolchain search paths for —
// hardcoding Xcode's `usr/lib/swift` directories would be the alternative. The
// dynamic build resolves those symbols from the dylib and needs nothing extra.
// Its slice holds the same `Sentry.framework`, so `-framework Sentry` is
// unchanged.
//
// Nothing below downloads anything on a non-Apple machine: the configuration is
// resolved only when [unzipSentryCocoa] actually runs, and that only happens as
// a dependency of an Apple link task.

val sentryCocoa: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}

dependencies {
    sentryCocoa("io.sentry.cocoa:Sentry-Dynamic.xcframework:${libs.versions.sentryCocoa.get()}@zip")
}

val sentryCocoaDir: Provider<Directory> = layout.buildDirectory.dir("sentry-cocoa")

val unzipSentryCocoa = tasks.register<Sync>("unzipSentryCocoa") {
    description = "Unpacks Sentry.xcframework so Apple link steps can find Sentry.framework."
    // Wrapped in a lambda so the configuration is resolved at execution time.
    from({ zipTree(sentryCocoa.singleFile) })
    into(sentryCocoaDir)
}

/**
 * The `.xcframework` slice for [konanTargetName], as the linker's `-F` argument
 * wants it: the directory *containing* `Sentry.framework`, not the framework.
 */
fun sentryCocoaSlice(konanTargetName: String): String? = when (konanTargetName) {
    "ios_arm64" -> "ios-arm64"
    "ios_simulator_arm64", "ios_x64" -> "ios-arm64_x86_64-simulator"
    else -> null
}

fun Project.linkSentryCocoaFramework() {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            targets.withType<KotlinNativeTarget>().configureEach {
                val slice = sentryCocoaSlice(konanTarget.name) ?: return@configureEach
                val searchPath = rootProject.layout.buildDirectory
                    .dir("sentry-cocoa/Sentry-Dynamic.xcframework/$slice")
                    .get().asFile.absolutePath
                binaries.configureEach {
                    linkerOpts("-F", searchPath)
                    // Test executables are *run* here, by Gradle, outside any app
                    // bundle — so they also need a runtime search path, or the
                    // dynamic framework they just linked against is missing at
                    // launch (`dyld: Library not loaded: @rpath/Sentry.framework/Sentry`).
                    // Deliberately not applied to the `Shared` framework: that one
                    // is embedded by Xcode, which supplies Sentry itself, and an
                    // absolute path into this machine's build directory has no
                    // business in a shipped binary.
                    if (outputKind == NativeOutputKind.TEST) {
                        linkerOpts("-rpath", searchPath)
                    }
                    linkTaskProvider.configure { dependsOn(rootProject.tasks.named("unzipSentryCocoa")) }
                }
            }
        }
    }
}
