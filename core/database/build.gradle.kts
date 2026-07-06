plugins {
    id("muviss.kmp.library")
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("MuvissDatabase") {
            packageName.set("com.codingpit.muviss.core.database")
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.models)
            api(projects.core.common)
            api(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutinesExtensions)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.androidDriver)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqliteDriver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.nativeDriver)
        }
    }
}
