// Detekt's own custom rule set (#107): `ForbiddenViewModelScopeLaunch` is what
// actually enforces CLAUDE.md's "every viewModelScope launch goes through
// launchReporting/launchInReporting" rule — a bare `viewModelScope.launch { }`
// compiled fine and looked identical at a glance, so review alone kept
// missing it. Consumed the same way `libs.detekt.composeRules` already is
// (`config/detekt/detekt.yml` and this repo's root `build.gradle.kts`, which
// adds every module's `detektPlugins` dependency in one `allprojects` block —
// except this module's own, or it would depend on itself).
//
// A plain `kotlinJvm` module rather than `muviss.kmp.library`: detekt only
// ever runs on the JVM (the Gradle plugin itself, and the `Detekt`/`DetektCli`
// tasks), and a custom `Rule` is written against detekt-api/Kotlin PSI types
// that have no Kotlin/Native or JS/Wasm target — there is nothing here for a
// second platform to compile.
plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
