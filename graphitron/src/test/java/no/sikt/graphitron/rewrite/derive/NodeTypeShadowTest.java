package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import graphql.schema.GraphQLObjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiConsumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The binder for the two live spellings of nodehood. {@link NodeDeclaration#isNodeType} answers it in
 * Java for four walk consumers, and {@code intent_node_type} answers it in the store; this compares
 * them over the same schema against the same catalog and requires the same set.
 *
 * <p>It is here because nothing else binds the pair. The {@code intent_type_domain} shadow is not a
 * binder for it: a transitive closure is not injective on its seeds, so a nodehood disagreement about
 * a type reachable through any field edge disappears into the closure. This comparison is at the
 * membership grain, where a disagreement about one type is a difference of one row.
 *
 * <p><b>When this may go.</b> When the four walk consumers ({@code SchemaReachability},
 * {@code ArrivalIndex}, {@code KeyNodeSynthesiser}, {@code CatalogBuilder}) read the store instead of
 * the predicate. There is then one spelling and nothing to bind, and the Java one retires with this
 * test.
 */
@PipelineTier
class NodeTypeShadowTest {

    private static final String DIRECTIVES = "interface Node { id: ID! }\n";

    /**
     * Both arms in one schema: a declared {@code @node}, an inferred {@code @table} node implementor
     * over a metadata-publishing table, and two types that are neither. The catalog is the fixture
     * one, so the inferred arm has real constants to conjoin rather than seeded ones.
     */
    @Test
    void theDerivationAgreesWithThePredicateOnBothArms(@TempDir Path tmp) {
        String sdl = DIRECTIVES + """
            type Query { film: Film, pairing: Pairing, plain: Plain }

            type Film implements Node @node {
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
        withBothSpellings(tmp, sdl, (derived, predicate) -> {
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
    void aNodeImplementorOverATableWithoutMetadataIsANodeOnNeitherSpelling(@TempDir Path tmp) {
        String sdl = DIRECTIVES + """
            type Query { hollow: Hollow }

            type Hollow implements Node @table(name: "language") {
              id: ID!
            }
            """;
        withBothSpellings(tmp, sdl, (derived, predicate) -> {
            assertThat(predicate).isEmpty();
            assertThat(derived).isEmpty();
        });
    }

    /**
     * With no catalog captured the derived arm has no metadata rows to conjoin, so it answers
     * {@code @node} presence alone. That is the same set the predicate answers built on no catalog,
     * and the case is what says the derivation's inference is over captured facts rather than over a
     * probe that runs whether or not the catalog reached the store.
     */
    @Test
    void withNoCatalogCapturedBothSpellingsFallToNodePresence(@TempDir Path tmp) {
        String sdl = DIRECTIVES + """
            type Query { film: Film, pairing: Pairing }

            type Film implements Node @node { id: ID! }

            type Pairing implements Node @table(name: "film_actor") { id: ID! }
            """;
        try (var store = CapturedStore.of(tmp, sdl)) {
            var predicate = nodeTypesBy(new NodeDeclaration(null), sdl);
            assertThat(predicate).containsExactly("Film");
            assertThat(derivedNodeTypes(store)).containsExactlyInAnyOrderElementsOf(predicate);
        }
    }

    /**
     * Runs both spellings over one schema and one catalog: the derivation reads the store the capture
     * filled, the predicate reads the same jOOQ package live off the assembled schema.
     */
    private static void withBothSpellings(Path tmp, String sdl,
                                          BiConsumer<Set<String>, Set<String>> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq)) {
            body.accept(derivedNodeTypes(store), nodeTypesBy(TestSchemaHelper.nodeDeclaration(), sdl));
        }
    }

    /** The store's answer: the membership relation's rows for the captured graph. */
    private static Set<String> derivedNodeTypes(CapturedStore store) {
        return new LinkedHashSet<>(store.dsl()
            .select(INTENT_NODE_TYPE.TYPE_NAME)
            .from(INTENT_NODE_TYPE)
            .fetch(INTENT_NODE_TYPE.TYPE_NAME));
    }

    /**
     * The predicate's answer, over the assembled schema's object types. The assembled-schema overload
     * is the one the four walk consumers call, so it is the overload the comparison has to use.
     */
    private static Set<String> nodeTypesBy(NodeDeclaration nodes, String sdl) {
        var assembled = TestSchemaHelper.buildBundle(sdl).assembled();
        var named = new LinkedHashSet<String>();
        for (var type : assembled.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType object && nodes.isNodeType(object)) {
                named.add(object.getName());
            }
        }
        return named;
    }
}
