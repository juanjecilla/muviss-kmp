plugins {
    id("muviss.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.settings.api)
            implementation(projects.feature.settings.domain)
            implementation(projects.core.common)
            implementation(projects.core.database)
            implementation(projects.core.network)
            implementation(projects.feature.collection.api)
            implementation(projects.feature.progress.api)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.sqldelight.coroutinesExtensions)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqliteDriver)
            // Test-only: the idempotent-import integration test wires the real
            // collection and progress repositories against one shared in-memory
            // database — mirrors profile:data's own test-only dependency on
            // collection/progress's domain+data (see that module's build.gradle.kts).
            implementation(projects.feature.collection.domain)
            implementation(projects.feature.collection.data)
            implementation(projects.feature.progress.domain)
            implementation(projects.feature.progress.data)
        }
    }
}
