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

// Feature slices (each: :api / :domain / :data / :ui)
listOf("search", "collection", "progress", "profile", "settings").forEach { feature ->
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
