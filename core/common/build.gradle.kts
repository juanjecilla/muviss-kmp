plugins {
    id("muviss.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
            api(libs.koin.core)
        }
        // Sentry KMP publishes real android/jvm/iOS implementations and no-op
        // stubs for js/wasmJs. We scope the dependency to the targets that get
        // a real implementation ourselves (see CrashReporter's actuals) so the
        // web targets never resolve it at all.
        androidMain.dependencies { implementation(libs.sentry.kotlinMultiplatform) }
        iosMain.dependencies { implementation(libs.sentry.kotlinMultiplatform) }
        jvmMain.dependencies { implementation(libs.sentry.kotlinMultiplatform) }
    }
}
