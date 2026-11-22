// Registers `resolveAllDependencies` on every project: it resolves every
// resolvable configuration, so Gradle sees (and, with
// `--write-verification-metadata`, records) every artifact the build can ask for,
// not only the ones one lane happens to touch (#480). Used to regenerate
// gradle/verification-metadata.xml, see scripts/gradle/update-verification.sh.
//
//   ./gradlew --init-script scripts/gradle/resolve-all.init.gradle.kts resolveAllDependencies
allprojects {
    tasks.register("resolveAllDependencies") {
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doLast {
            var skipped = 0
            configurations
                .filter { it.isCanBeResolved }
                .forEach { config ->
                    try {
                        config.resolve()
                    } catch (e: Exception) {
                        // Not an error for this task, and it cannot be: a bare resolve() of a whole
                        // configuration fails for hundreds of them even on macOS (663 on 2026-11-04,
                        // `TypedResolveException`, `:app:androidApp:debugRuntimeClasspath` among them),
                        // because the real tasks resolve with attributes this call does not pass.
                        // What this task adds is best effort; the CI lanes update-verification.sh runs
                        // are what must resolve, a lane failure there is a warning, and a checksum still
                        // missing fails CI's verification on the PR rather than being accepted unchecked.
                        skipped++
                        logger.info("skipped ${project.path}:${config.name}: ${e.javaClass.simpleName}")
                    }
                }
            // Said at the default log level, so a count that jumps is visible without --info.
            if (skipped > 0) logger.lifecycle("resolveAllDependencies ${project.path}: $skipped configuration(s) did not resolve on this host (names with --info)")
        }
    }
}
