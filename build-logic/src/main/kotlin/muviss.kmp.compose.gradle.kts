@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.ComposePlugin

// Convention for UI-bearing multiplatform modules: layers Compose Multiplatform
// on top of the base library convention.
plugins {
    id("muviss.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val compose = ComposePlugin.Dependencies(project)

// Every UI module owns its strings (`src/commonMain/composeResources/values*/strings.xml`)
// and gets an internal `Res` in a package derived from its path, exactly like the
// Android namespace — so `:feature:search:ui` reads
// `com.codingpit.muviss.feature.search.ui.generated.resources.Res` and two modules'
// generated classes never collide. Cross-module copy travels as `UiText`
// (`:core:designsystem`), never as a peer's `Res`. (`configure<>` because the
// `compose` val above shadows the extension accessor.)
configure<ComposeExtension> {
    resources {
        packageOfResClass = "com.codingpit.muviss." +
            path.removePrefix(":").replace(":", ".").replace("-", "") +
            ".generated.resources"
        publicResClass = false
    }
}

kotlin {
    // `com.android.kotlin.multiplatform.library` does not process Android
    // resources unless asked, and Compose Resources ride on them: without
    // this, every `composeResources` file — strings *and* the bundled fonts —
    // is silently left out of the APK. JVM tests read them off the classpath
    // and pass regardless; Android throws `MissingResourceException` the first
    // time a string is looked up, and `Font(...)` fell back to the system font
    // without a word. Only a launch on a device shows it (EPIC 31).
    androidLibrary {
        androidResources.enable = true
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        // Every UI module can write Compose tests without restating the wiring.
        commonTest.dependencies {
            implementation(compose.uiTest)
        }
        // `runComposeUiTest` renders through Skiko, whose native binary ships in
        // the desktop artifact rather than in `ui-test` — so the JVM run is
        // where Compose UI tests (and every golden-image test) execute.
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

// Golden images live in the module's own `src/jvmTest/resources/screenshots`,
// because recording has to *write* them and a classpath URL points at build
// output. `-Precord` (or MUVISS_RECORD_GOLDENS=1) rewrites instead of asserts.
val recordGoldens: Boolean = providers.gradleProperty("record").isPresent
val goldenDir: String = layout.projectDirectory.dir("src/jvmTest/resources/screenshots").asFile.absolutePath

tasks.withType<Test>().configureEach {
    // Compose Resources picks `values-<lang>` from the JVM's default locale,
    // which is the host machine's — a Spanish-language Mac renders "Reintentar"
    // where every test asserts "Retry", while CI's Ubuntu passes. Pinned like
    // `darkTheme` is in golden tests; a test about another locale sets it itself.
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    systemProperty("muviss.golden.dir", goldenDir)
    systemProperty("muviss.golden.record", recordGoldens.toString())
}
