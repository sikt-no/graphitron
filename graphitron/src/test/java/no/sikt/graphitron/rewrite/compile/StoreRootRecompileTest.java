package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.schema.FixedRuntimeEmitter;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The roots the store states over several tables or as a node fetcher, in the dev loop's recompile
 * graph projected from a store-backed plan: an edit to what a root's fetcher calls recompiles that
 * fetcher, a participant's projection class and the condition class its branches call for a union or
 * interface root, the node dispatch unit for a node root, and an edit to a class the fetcher does not
 * call does not.
 */
@PipelineTier
class StoreRootRecompileTest {

    private static final String SDL = """
        interface Node { id: ID! }
        interface Person { lastName: String }
        type Customer implements Node & Person @table(name: "customer") @node {
            id: ID!
            lastName: String @field(name: "last_name")
        }
        type Staff implements Node & Person @table(name: "staff") @node {
            id: ID!
            lastName: String @field(name: "last_name")
        }
        union Occupant = Customer | Staff
        type Query {
            occupants(lastName: String @field(name: "last_name")): [Occupant!]!
            people: [Person!]!
            node(id: ID!): Node
        }
        """;

    private static final GeneratedUnits UNITS = new GeneratedUnits(testContext().outputPackage());

    private static CompileDependencyGraph graph;

    @BeforeAll
    static void projectGraph(@TempDir Path directory) {
        var bundle = TestSchemaHelper.buildBundle(SDL);
        var plan = TestSchemaHelper.storeBackedPlan(directory, SDL);
        graph = PlanCompileGraph.fromPlan(plan, FixedRuntimeEmitter.render(testContext().outputPackage()),
            bundle.model());
    }

    /** What an edit to {@code edited}'s signature recompiles. */
    private static Set<String> recompiledBy(String edited) {
        return RecompileSet.compute(graph, Set.of(edited), Set.of(edited));
    }

    private static final String QUERY_FETCHERS = UNITS.fetchers("Query").fqcn();

    @Test
    void anEditToAParticipantsProjectionRecompilesTheUnionAndInterfaceRoots() {
        assertThat(recompiledBy(UNITS.typeClass("Customer").fqcn())).contains(QUERY_FETCHERS);
        assertThat(recompiledBy(UNITS.typeClass("Staff").fqcn())).contains(QUERY_FETCHERS);
    }

    @Test
    void anEditToTheConditionClassABranchCallsRecompilesTheUnionRoot() {
        assertThat(recompiledBy(UNITS.conditions("Query").fqcn())).contains(QUERY_FETCHERS);
    }

    @Test
    void anEditToTheNodeDispatchRecompilesTheNodeRoot() {
        assertThat(recompiledBy(UNITS.queryNodeFetcher().fqcn())).contains(QUERY_FETCHERS);
    }

    @Test
    void anEditToAClassTheFetcherDoesNotCallLeavesItAlone() {
        assertThat(recompiledBy(UNITS.rootUnit("Graphitron").fqcn())).doesNotContain(QUERY_FETCHERS);
    }
}
