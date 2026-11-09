import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The red fixtures are string literals built at test time, never a committed
 * module that would have to be kept failing on purpose (#483).
 */
class ModuleGraphTest {
    private fun edges(vararg pairs: String) = pairs.map { it.split(" -> ").let { (from, to) -> ModuleGraph.Edge(from, to) } }

    @Test
    fun the_layers_of_one_feature_are_allowed() {
        val ok = edges(
            ":feature:search:ui -> :feature:search:domain",
            ":feature:search:ui -> :feature:search:api",
            ":feature:search:data -> :feature:search:domain",
            ":feature:search:domain -> :feature:search:api",
        )
        assertEquals(emptyList(), ModuleGraph.check(ok))
    }

    @Test
    fun ui_never_reaches_data_and_api_never_reaches_down() {
        val problems = ModuleGraph.check(edges(":feature:search:ui -> :feature:search:data", ":feature:search:api -> :feature:search:domain", ":feature:search:domain -> :feature:search:data"))
        assertEquals(3, problems.size)
    }

    @Test
    fun a_peer_is_reached_through_api_only() {
        assertEquals(emptyList(), ModuleGraph.check(edges(":feature:cowatch:data -> :feature:collection:api")))
        val problems = ModuleGraph.check(edges(":feature:cowatch:data -> :feature:collection:domain", ":feature:cowatch:ui -> :feature:collection:ui"))
        assertEquals(2, problems.size)
        assertTrue(problems.all { "peer features are reached through :api only" in it })
    }

    @Test
    fun infrastructure_sits_below_features() {
        assertEquals(emptyList(), ModuleGraph.check(edges(":core:sync -> :feature:collection:api", ":core:sync -> :models")))
        val problems = ModuleGraph.check(edges(":core:sync -> :feature:collection:data", ":models -> :feature:search:ui"))
        assertEquals(2, problems.size)
    }

    @Test
    fun hosts_use_api_but_the_composition_root_wires_everything() {
        assertEquals(emptyList(), ModuleGraph.check(edges(":app:shared -> :feature:search:data", ":app:shared -> :feature:search:ui", ":app:androidApp -> :feature:search:api")))
        assertEquals(1, ModuleGraph.check(edges(":app:androidApp -> :feature:search:data")).size)
    }

    @Test
    fun an_exception_excuses_exactly_its_edge() {
        val bad = edges(":core:sync -> :feature:collection:data", ":core:sync -> :feature:progress:data")
        val excuse = ModuleGraph.Exception(":core:sync", ":feature:collection:data", "#999 predates the check")
        val problems = ModuleGraph.check(bad, listOf(excuse))
        assertEquals(1, problems.size)
        assertTrue(":feature:progress:data" in problems.single())
    }

    @Test
    fun an_exception_for_a_fixed_edge_is_itself_a_violation() {
        val gone = ModuleGraph.Exception(":core:sync", ":feature:collection:data", "#999")
        val problems = ModuleGraph.check(edges(":core:sync -> :models"), listOf(gone))
        assertEquals(1, problems.size)
        assertTrue("no longer applies" in problems.single())
    }

    @Test
    fun an_exception_without_a_reason_excuses_nothing() {
        val bad = edges(":core:sync -> :feature:collection:data")
        val problems = ModuleGraph.check(bad, listOf(ModuleGraph.Exception(":core:sync", ":feature:collection:data", " ")))
        assertEquals(2, problems.size)
        assertTrue(problems.any { "infrastructure may not depend" in it })
        assertTrue(problems.any { "needs a reason" in it })
    }
}
