plugins {
    id("muviss.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, not implementation: the @Serializable enums in this module
            // (MediaType, WatchStatus, ProductionStatus...) expose their
            // generated Companion as a KSerializer supertype. On the JVM
            // backend consumers get away with implementation-scoping this
            // because class-file resolution is lazy, but the JS/Wasm klib
            // backend fully resolves the Companion's supertype hierarchy at
            // compile time, so every downstream module needs
            // kotlinx-serialization-core on its own compile classpath too
            // (see #10 — this was breaking :core:model:compileKotlinJs/Wasm).
            api(libs.kotlinx.serializationJson)
        }
    }
}
