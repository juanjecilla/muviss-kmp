@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import org.jetbrains.compose.ComposePlugin

// Convention for UI-bearing multiplatform modules: layers Compose Multiplatform
// on top of the base library convention.
plugins {
    id("muviss.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val compose = ComposePlugin.Dependencies(project)

kotlin {
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
    systemProperty("muviss.golden.dir", goldenDir)
    systemProperty("muviss.golden.record", recordGoldens.toString())
}
