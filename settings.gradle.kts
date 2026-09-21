rootProject.name = "Muviss"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()

        // Sentry's iOS SDK is a binary framework distributed as a GitHub
        // release asset, not a Maven artifact — `sentry-kotlin-multiplatform`'s
        // iOS klibs are only a cinterop wrapper over it and carry
        // `linkerOpts=-framework Sentry`, so every Apple *executable* link
        // (notably the `linkDebugTest*` binaries `./gradlew build` produces)
        // fails with `ld: framework 'Sentry' not found` unless something puts
        // `Sentry.framework` on the linker's search path. An `ivy` repository
        // over the release-download URLs makes that zip an ordinary resolvable
        // dependency, so Gradle's own cache and checksum handling apply
        // instead of a hand-rolled download task. The root build script
        // unzips it and points `-F` at the right slice; see `build.gradle.kts`.
        ivy("https://github.com/getsentry/sentry-cocoa/releases/download") {
            patternLayout { artifact("[revision]/[artifact].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("io.sentry.cocoa") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Shared domain + infra
include(":models")
include(":core:common")
include(":core:model")
include(":core:database")
include(":core:network")
include(":core:designsystem")
include(":core:billing")
include(":core:sync")
include(":core:testing")

// Feature slices (each: :api / :domain / :data / :ui)
listOf("search", "collection", "progress", "profile", "settings", "triage", "cowatch").forEach { feature ->
    listOf("api", "domain", "data", "ui").forEach { layer ->
        include(":feature:$feature:$layer")
    }
}

// Apps + server
include(":app:androidApp")
include(":app:desktopApp")
include(":app:shared")
include(":app:webApp")
include(":server")
