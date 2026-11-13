package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import graphql.schema.GraphQLObjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiConsumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The binder for the two live spellings of nodehood. {@link NodeDeclaration#isNodeType} answers it in
 * Java for the walk consumers, and {@code graphitron_node} answers it in the store; this compares
 * them over the same schema against the same catalog and requires the same set.
 *
 * <p>It is here because nothing else binds the pair. The {@code intent_type_domain} shadow is not a
 * binder for it: a transitive closure is not injective on its seeds, so a nodehood disagreement about
 * a type reachable through any field edge disappears into the closure. This comparison is at the
 * membership grain, where a disagreement about one type is a difference of one row.
 *
 * <p><b>When this may go.</b> When the three walk consumers ({@code SchemaReachability},
 * {@code ArrivalIndex}, {@code KeyNodeSynthesiser}) read the store instead of
 * the predicate. There is then one spelling and nothing to bind, and the Java one retires with this
 * test.
 */
@PipelineTier
class NodeTypeShadowTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String DIRECTIVES = "interface Node { id: ID! }\n";

    /**
     * Both arms in one schema: a declared {@code @node}, an inferred {@code @table} node implementor
     * over a metadata-publishing table, and two types that are neither. The catalog is the fixture
     * one, so the inferred arm has real constants to conjoin rather than seeded ones.
     */
    @Test
    void theDerivationAgreesWithThePredicateOnBothArms() {
        String sdl = DIRECTIVES + """
            type Query { film: Film, pairing: Pairing, plain: Plain }

            type Film implements Node @table(name: "film") @node {
              id: ID!
              title: String
            }

            type Pairing implements Node @table(name: "film_actor") {
              id: ID!
            }

            type Plain @table(name: "language") {
              name: String
            }

            type Loose { note: String }
            """;
        withBothSpellings(sdl, (derived, predicate) -> {
            assertThat(derived).as("the derivation answered something").isNotEmpty();
            assertThat(predicate).as("the predicate answered something").isNotEmpty();
            assertThat(derived).containsExactlyInAnyOrderElementsOf(predicate);
            assertThat(derived).contains("Film", "Pairing").doesNotContain("Plain", "Loose");
        });
    }

    /**
     * The tightening is the point of the derived arm, so it gets its own case: a {@code @table} type
     * implementing {@code Node} over a table whose class publishes no node metadata is not a node on
     * either spelling. The store's old over-approximation called it one, which is what let a
     * disagreement here go unnoticed.
     */
    @Test
    void aNodeImplementorOverATableWithoutMetadataIsANodeOnNeitherSpelling() {
        String sdl = DIRECTIVES + """
            type Query { hollow: Hollow }

            type Hollow implements Node @table(name: "language") {
              id: ID!
            }
            """;
        withBothSpellings(sdl, (derived, predicate) -> {
            assertThat(predicate).isEmpty();
            assertThat(derived).isEmpty();
        });
    }

    /**
     * With no catalog captured no {@code @table} binds a table, so neither spelling finds a node, a
     * {@code @node} over a {@code @table} included: a node is a row of a table, and the derivation's
     * answer is over captured facts rather than over the directive alone.
     */
    @Test
    void withNoCatalogCapturedNeitherSpellingFindsANode() {
        String sdl = DIRECTIVES + """
            type Query { film: Film, pairing: Pairing }

            type Film implements Node @table(name: "film") @node { id: ID! }

            type Pairing implements Node @table(name: "film_actor") { id: ID! }
            """;
        assertThat(nodeTypesBy(new NodeDeclaration(null), sdl)).isEmpty();
        assertThat(derivedNodeTypes(sdl, null)).isEmpty();
    }

    /**
     * A {@code @node} whose {@code @table} binds no table is a node on neither spelling: one with no
     * {@code @table}, one over a bare name two schemas declare, and a root operation type, which no
     * {@code @table} binds.
     */
    @Test
    void aNodeOverNoBoundTableIsANodeOnNeitherSpelling() {
        String sdl = DIRECTIVES + """
            type Query { film: Film, floating: Floating, ev: Ev }

            type Mutation @table(name: "film") @node { noop: String }

            type Film implements Node @table(name: "film") @node { id: ID! }

            type Floating implements Node @node { id: ID! }

            type Ev implements Node @table(name: "event") @node { id: ID! }
            """;
        withBothSpellings(sdl, (derived, predicate) -> {
            assertThat(derived).containsExactlyInAnyOrderElementsOf(predicate);
            assertThat(derived).containsExactly("Film");
        });
    }

    /**
     * Runs both spellings over one schema and one catalog: the derivation reads the store the capture
     * filled, the predicate reads the same jOOQ package live off the assembled schema.
     */
    private static void withBothSpellings(String sdl, BiConsumer<Set<String>, Set<String>> body) {
        body.accept(derivedNodeTypes(sdl, CatalogInput.of(testContext())),
            nodeTypesBy(TestSchemaHelper.nodeDeclaration(), sdl));
    }

    /** The store's answer: the membership relation's rows for {@code sdl}'s graph, over {@code jooq}. */
    private static Set<String> derivedNodeTypes(String sdl, CatalogInput jooq) {
        return FACTS.readInline("node-type-shadow", sdl, jooq, graph -> new LinkedHashSet<>(
            graph.handle().dsl()
                .select(GRAPHITRON_NODE.TYPE_NAME)
                .from(GRAPHITRON_NODE)
                .where(GRAPHITRON_NODE.GRAPH_NAME.eq(graph.handle().graphName()))
                .fetch(GRAPHITRON_NODE.TYPE_NAME)));
    }

    /**
     * The predicate's answer, over the assembled schema's object types. The assembled-schema overload
     * is the one the four walk consumers call, so it is the overload the comparison has to use.
     */
    private static Set<String> nodeTypesBy(NodeDeclaration nodes, String sdl) {
        var assembled = TestSchemaHelper.walkBundle(sdl).assembled();
        var named = new LinkedHashSet<String>();
        for (var type : assembled.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType object && nodes.isNodeType(object)) {
                named.add(object.getName());
            }
        }
        return named;
    }
}
