import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.app.shared)

            implementation(libs.compose.ui)
        }
        // `webpack.config.d/copy-sqljs-wasm.js` needs `copy-webpack-plugin`
        // resolvable from this module's webpack execution context — see that
        // file for why (`sql-wasm.wasm` has to land at the site root for
        // `:core:database`'s web persistence driver to load it; see EPIC 13
        // and `docs/adr/0008-migration-baseline-and-deferred-web-persistence.md`).
        jsMain.dependencies {
            implementation(devNpm("copy-webpack-plugin", "12.0.2"))
        }
        wasmJsMain.dependencies {
            implementation(devNpm("copy-webpack-plugin", "12.0.2"))
        }
    }
}
