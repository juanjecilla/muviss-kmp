/**
 * The vertical-slice rule of ADR 0004, as a pure function over declared project
 * dependencies (#483). It reads the Gradle project graph, not imports: a
 * declared-but-unused edge is still an edge, and it is the one a later change
 * quietly starts using.
 *
 * Rules, for edges out of main (non-test) configurations:
 * 1. Inside a feature, `api` depends on no sibling, `domain` on `api`, `data` on
 *    `api` and `domain`, `ui` on `api` and `domain` (`ui -> domain <- data`).
 * 2. A feature reaches a peer feature through the peer's `:api` only.
 * 3. `:models` and `:core:*` never depend on a feature's `domain`, `data` or `ui`:
 *    infrastructure sits below features.
 * 4. A platform host (`:app:androidApp` ...) reaches features through `:api` only.
 *    `:app:shared` is the composition root and may wire any layer.
 *
 * An [Exception] is a dated, issue-linked edge that predates the check. It is
 * read here so the list is one place and fails once the edge is fixed (a stale
 * exception is itself a violation), instead of rotting. One with no reason
 * excuses nothing and is reported.
 */
object ModuleGraph {
    data class Edge(val from: String, val to: String)

    data class Exception(val from: String, val to: String, val reason: String)

    private val featureModule = Regex("""^:feature:([^:]+):(api|domain|data|ui)$""")
    private val layerMayUse = mapOf(
        "api" to emptySet(),
        "domain" to setOf("api"),
        "data" to setOf("api", "domain"),
        "ui" to setOf("api", "domain"),
    )

    private fun feature(path: String) = featureModule.matchEntire(path)?.destructured?.let { (name, layer) -> name to layer }

    /** Why [edge] breaks the rules, or null when it is allowed. */
    fun violation(edge: Edge): String? {
        val from = feature(edge.from)
        val to = feature(edge.to)
        if (to != null) {
            val (toName, toLayer) = to
            if (from != null) {
                val (fromName, fromLayer) = from
                if (fromName == toName) {
                    return "$fromLayer may not depend on $toLayer within :feature:$fromName".takeUnless { toLayer in layerMayUse.getValue(fromLayer) }
                }
                return "peer features are reached through :api only".takeUnless { toLayer == "api" }
            }
            if (toLayer != "api") {
                if (edge.from == ":models" || edge.from.startsWith(":core:")) return "infrastructure may not depend on a feature's $toLayer"
                if (edge.from.startsWith(":app:") && edge.from != ":app:shared") return "a platform host reaches features through :api only"
            }
        }
        return null
    }

    /** One line per problem; empty when the graph holds. */
    fun check(edges: Collection<Edge>, exceptions: Collection<Exception> = emptyList()): List<String> {
        // An exception is the record of why an edge is tolerated; without the why it excuses nothing.
        val (reasoned, unreasoned) = exceptions.partition { it.reason.isNotBlank() }
        val excused = reasoned.map { Edge(it.from, it.to) }.toSet()
        val problems = edges.distinct().sortedBy { it.from + it.to }.mapNotNull { edge ->
            violation(edge)?.takeUnless { edge in excused }?.let { "${edge.from} -> ${edge.to}: $it" }
        }
        val present = edges.toSet()
        val missingReason = unreasoned.map { "exception ${it.from} -> ${it.to} needs a reason after `#`" }
        val stale = reasoned.filter { Edge(it.from, it.to) !in present || violation(Edge(it.from, it.to)) == null }
            .map { "exception ${it.from} -> ${it.to} no longer applies (${it.reason}); delete it" }
        return problems + missingReason + stale
    }
}
