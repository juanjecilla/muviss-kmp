import org.gradle.api.plugins.BasePluginExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// Base convention for every shared Kotlin Multiplatform module (non-UI).
// Applies the KMP + Android-library plugins, declares the full target set,
// and derives the Android namespace from the Gradle project path.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // The KMP library plugin creates no lint tasks of its own; this adds
    // `lint`, and `:app:androidApp`'s `checkDependencies` then covers the
    // module too (EPIC 31b #164).
    id("com.android.lint")
    // Supplies `BasePluginExtension.archivesName` below (#493).
    base
}

// SDK levels come from the version catalog, like :app:androidApp's, so a
// compileSdk bump is one line and cannot leave the libraries behind (#71).
// Precompiled scripts get no `libs` accessor, hence the lookup by name.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun sdkLevel(name: String): Int = catalog.findVersion(name).get().requiredVersion.toInt()

val moduleNamespace: String = "com.codingpit.muviss." +
    path.removePrefix(":").replace(":", ".").replace("-", "")

// The metadata compilations' klib name (`unique_name=<name>_commonMain`) comes from
// `archivesName`, falling back to `project.name` (KGP's `baseModuleName`). `project.name`
// is `api`/`domain`/`data`/`ui` in every feature, so the KLIB loader warned about the
// same name on every metadata compile that saw two features (#493). Naming it from the
// Gradle path is what the Android namespace above already does.
configure<BasePluginExtension> {
    archivesName.set(path.removePrefix(":").replace(":", "-"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        // The browser actuals call `external`/`js()` interop, which Kotlin 2.4
        // marks experimental. Opting in here, for this one target, keeps the
        // warning from needing a file-level @OptIn in every web actual (#485).
        compilerOptions {
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    android {
        namespace = moduleNamespace
        compileSdk = sdkLevel("android-compileSdk")
        minSdk = sdkLevel("android-minSdk")

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Warnings are errors in shipped code (#485). Main compilations only: the test
// compilations still call Compose's deprecated `runComposeUiTest`, whose v2 move
// changes test-dispatcher semantics and is its own piece of work (#492). A
// library warning that cannot be fixed gets a narrow `@Suppress` with a comment;
// `-PallowWarnings` switches the rule off for a local experiment, never for CI.
//
// Metadata compilations of shared source sets are checked too (#493), with one
// exception: `iosMain`. Our own module names are unique (`archivesName`, above), so
// what the KLIB loader still reports there is library skew, and it only shows up in
// the compilation that sees Apple's klibs next to the common ones:
//  - the same library in two Maven groups: Compose 1.12's `org.jetbrains.androidx.*`
//    forks (lifecycle 2.9.6, savedstate 1.3.6, navigationevent) against Google's
//    `androidx.*` artifacts (lifecycle 2.11.0, savedstate 1.4.0), and the
//    `annotation`/`collection` internals Compose pulls in. A catalog bump does not
//    merge groups, so this needs a dependency substitution across the Compose graph;
//  - `app.cash.sqldelight:runtime` and `androidx.compose.runtime:runtime` both
//    name their klib `runtime`, which no version alignment can change.
// The KLIB warning cannot be silenced per-warning, so `iosMain` stays out until that
// substitution exists (#670). Its code is compiled
// again by each concrete iOS target, so a problem in it is still caught there.
kotlin.targets.configureEach {
    compilations.configureEach {
        val klibSkewCompilation = target.platformType == KotlinPlatformType.common && name == "iosMain"
        if (!klibSkewCompilation && !name.contains("test", ignoreCase = true)) {
            compileTaskProvider.configure {
                compilerOptions.allWarningsAsErrors.set(!providers.gradleProperty("allowWarnings").isPresent)
            }
        }
    }
}
