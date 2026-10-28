// `verifyModuleGraph` (#483): ADR 0004's dependency rule over the declared
// project graph. Applied to the root project; wired into `check` there.
// The rule itself, and the exceptions that predate it, are in ModuleGraph.

val declarable = Regex("""(?i)^(api|implementation|compileOnly|runtimeOnly|\w+(Api|Implementation|CompileOnly|RuntimeOnly))$""")

val edges: Provider<List<String>> = provider {
    rootProject.subprojects.flatMap { sub ->
        sub.configurations
            .filter { declarable.matches(it.name) && !it.name.contains("test", ignoreCase = true) && !it.name.contains("baselineProfile", ignoreCase = true) }
            .flatMap { config -> config.dependencies.withType<ProjectDependency>().map { "${sub.path}\t${it.path}" } }
    }.distinct()
}

val exceptionsFile = layout.projectDirectory.file("config/module-graph-exceptions.txt")

tasks.register("verifyModuleGraph") {
    group = "verification"
    description = "Fails when a module dependency breaks ADR 0004's vertical-slice rule."
    val edgeLines = edges
    val exceptionLines = providers.fileContents(exceptionsFile).asText.orElse("")
    inputs.property("edges", edgeLines)
    inputs.property("exceptions", exceptionLines)
    doLast {
        val found = edgeLines.get().map { it.split('\t').let { (from, to) -> ModuleGraph.Edge(from, to) } }
        val known = exceptionLines.get().lines()
            .map { it.substringBefore('#').trim() to it.substringAfter('#', "").trim() }
            .filter { it.first.isNotEmpty() }
            .map { (spec, reason) -> spec.split(Regex("\\s+")).let { (from, to) -> ModuleGraph.Exception(from, to, reason) } }
        val problems = ModuleGraph.check(found, known)
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Module graph breaks ADR 0004:\n" + problems.joinToString("\n") { "  $it" } +
                    "\nFix the dependency, or (only for an edge that predates the check) add it with a reason to config/module-graph-exceptions.txt.",
            )
        }
    }
}
