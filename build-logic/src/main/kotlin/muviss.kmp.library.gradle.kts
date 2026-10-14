import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
}

// SDK levels come from the version catalog, like :app:androidApp's, so a
// compileSdk bump is one line and cannot leave the libraries behind (#71).
// Precompiled scripts get no `libs` accessor, hence the lookup by name.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun sdkLevel(name: String): Int = catalog.findVersion(name).get().requiredVersion.toInt()

val moduleNamespace: String = "com.codingpit.muviss." +
    path.removePrefix(":").replace(":", ".").replace("-", "")

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
    }

    androidLibrary {
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
