import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

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
}
