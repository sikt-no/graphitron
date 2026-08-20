package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static no.sikt.graphitron.rewrite.CapturedStore.withCapturedStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for {@code intent_type_domain}: what the SDL gatherer's rooted
 * traversal writes, stated as hand-written expectations over a real capture's rows.
 *
 * <p>A specification test rather than a shadow. The relation used to be pinned by diffing it against
 * the classification walk's own reachable set, which made the walk its specification; the seed rule
 * has since been decided from what the requirement says, and where the two now differ the difference
 * is the point rather than a drift. So each case states the membership it expects and why, and the
 * seed cases assert the widening explicitly.
 *
 * <p>Whole sets, not containment, wherever the schema is small enough to write one out: a seed arm
 * that quietly reaches further is a difference this anchor exists to catch, and a containment
 * assertion cannot see it. Two names ride along in every set. {@code String} and {@code Boolean} are
 * the argument types of the specification's own directives, which survive into the emitted schema
 * and therefore seed exactly as an author's directive definition does.
 *
 * <p>What a consumer makes of these rows is not asked here. The demand reductions join them and
 * their algebra is {@code no.sikt.graphitron.model.intent.DemandRuleTest}'s subject; the
 * authored-claim conflict detection joins them as its build-error population and
 * {@code no.sikt.graphitron.rewrite.derive.AuthoredClaimConflictsTest} carries that. What stands
 * here is that an author's schema reaches the membership the seed rule promises.
 */
@PipelineTier
class ClassificationDomainTest {

    private static final String NODE_INTERFACE = "interface Node { id: ID! }\n";

    @TempDir
    Path tmp;

    // ===== The closure =====

    @Test
    @DisplayName("the domain is the rooted closure and nothing else")
    void theDomainIsTheRootedClosureAndNothingElse() {
        var sdl = """
            type Query { film(match: FilmFilter): Film }
            type Film { title: String, rating: Rating }
            input FilmFilter { title: String }
            enum Rating { G, PG }
            type Unreached { note: String }
            input UnreachedInput { note: String }
            """;
        withCapturedStore(tmp, sdl, dsl ->
            assertThat(domain(dsl, CapturedStore.GRAPH))
                .as("every kind the closure reaches, and no type no edge arrives at")
                .containsExactlyInAnyOrder("Query", "Film", "FilmFilter", "Rating",
                    "String", "Boolean"));
    }

    @Test
    @DisplayName("a union's members and an interface's implementors are members")
    void compositeEdgesAreFollowedInBothDirections() {
        var sdl = """
            type Query { holder: Named, choice: Either }
            interface Named { name: String }
            type Titled implements Named { name: String }
            union Either = Titled | Numbered
            type Numbered { count: Int }
            """;
        withCapturedStore(tmp, sdl, dsl ->
            assertThat(domain(dsl, CapturedStore.GRAPH))
                .as("the interface-to-implementor edge and the union's members both descend")
                .containsExactlyInAnyOrder("Query", "Named", "Titled", "Either", "Numbered",
                    "String", "Int", "Boolean"));
    }

    // ===== The node seed, and the two widenings it is =====

    @Test
    @DisplayName("a type declaring the Relay contract is a member with no @table at all")
    void theNodeContractSeedsWithoutATableBinding() {
        var sdl = NODE_INTERFACE + """
            type Query { ping: String }
            type Detached implements Node { id: ID! }
            """;
        withCapturedStore(tmp, sdl, dsl -> {
            assertThat(domain(dsl, CapturedStore.GRAPH))
                .as("the declaration alone seeds; nothing reaches Detached by a field")
                .contains("Detached", "Node");
            assertThat(dsl.fetchCount(INTENT_NODE_TYPE,
                INTENT_NODE_TYPE.GRAPH_NAME.eq(CapturedStore.GRAPH)))
                .as("and nodehood itself is not claimed: the seed over-approximates on purpose")
                .isZero();
        });
    }

    @Test
    @DisplayName("a type declaring the Relay contract is a member over defective node metadata")
    void theNodeContractSeedsOverATableThatPublishesNoNodeMetadata() {
        var sdl = NODE_INTERFACE + """
            type Query { ping: String }
            type Hollow implements Node @table(name: "language") { id: ID! }
            """;
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            assertThat(domain(store.dsl(), CapturedStore.GRAPH))
                .as("a bound table publishing no node metadata does not unseed the declaration")
                .contains("Hollow");
            assertThat(store.dsl().select(INTENT_NODE_TYPE.TYPE_NAME).from(INTENT_NODE_TYPE)
                .where(INTENT_NODE_TYPE.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .fetchSet(0, String.class))
                .as("inference declines it, which is what the member now gets diagnostics about")
                .doesNotContain("Hollow");
        }
    }

    @Test
    @DisplayName("a @node carrier and an authored @key carrier each seed on their own arm")
    void declaredNodehoodAndAuthoredKeysSeed() {
        // @key is declared in the fixture rather than arriving through @link, because this
        // capture reads a bare parse: what the arm is about is an authored carrier, and a subgraph
        // author writing federation SDL by hand declares it exactly like this.
        var sdl = NODE_INTERFACE + """
            directive @key(fields: String!) repeatable on OBJECT
            type Query { ping: String }
            type Declared implements Node @node { id: ID! }
            type Entity @key(fields: "id") { id: ID! }
            """;
        withCapturedStore(tmp, sdl, dsl ->
            assertThat(domain(dsl, CapturedStore.GRAPH))
                .as("both arms reach a type no field returns")
                .contains("Declared", "Entity"));
    }

    // ===== The directive seed =====

    @Test
    @DisplayName("a surviving directive's argument types seed; the bundled vocabulary's do not")
    void survivorshipIsReadFromTheDefinitionsOwnSource() {
        var sdl = """
            directive @mine(shape: MyShape!) on FIELD_DEFINITION
            input MyShape { note: String }
            type Query { ping: String @mine(shape: {note: "x"}) }
            """;
        withCapturedStore(tmp, sdl, dsl -> {
            var members = domain(dsl, CapturedStore.GRAPH);
            assertThat(members)
                .as("the author's own definition is re-declared in the emitted schema, so its "
                    + "argument type is part of that structure")
                .contains("MyShape");
            assertThat(members)
                .as("graphitron's bundled vocabulary is build-time only, so its support types are "
                    + "reached by nothing here")
                .doesNotContain("MutationType", "ExternalCodeReference", "ErrorHandler");
        });
    }

    // ===== The expansion's own shapes =====

    @Test
    @DisplayName("the connection expansion's minted shapes are members")
    void theExpansionsMintedShapesAreMembers() {
        var sdl = """
            type Query { films: [Film!] @asConnection }
            type Film { title: String }
            """;
        withCapturedStore(tmp, sdl, dsl ->
            assertThat(domain(dsl, CapturedStore.GRAPH))
                .as("the schema the store describes carries the expansion, so the traversal does too")
                .contains("QueryFilmsConnection", "QueryFilmsEdge", "PageInfo", "Film"));
    }

    // ===== The partition and the cliff =====

    @Test
    @DisplayName("the domain is scoped to its own graph")
    void theDomainIsScopedToItsGraph() {
        var own = "type Query { own: Owned }\ntype Owned { a: String }\n";
        var sibling = "type Query { far: Distant }\ntype Distant { b: String }\n";
        try (var store = CapturedStore.of(tmp, "own", own).andGraph("sibling", sibling)) {
            assertThat(domain(store.dsl(), "own")).contains("Owned").doesNotContain("Distant");
            assertThat(domain(store.dsl(), "sibling")).contains("Distant").doesNotContain("Owned");
        }
    }

    @Test
    @DisplayName("a registry that did not assemble leaves the partition empty")
    void aRefusedAssemblyLeavesThePartitionEmpty() {
        // The traversal reads what assembly produced, so a document with no assembled schema has
        // nothing to walk. The declaration facts survive, which is what makes the emptiness
        // readable: it is the assembly verdict's consequence and not the census's absence.
        // Driven through FactCapture directly: the refused-schema fixtures are about the two
        // stages ahead of assembly, and a dangling type reference is refused by assembly alone.
        try (var store = no.sikt.graphitron.model.test.FactStores.inMemory()) {
            FactCapture.capture(store.dsl(), CapturedStore.graph(tmp),
                FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(tmp, "type Query { gone: Nope }\n"),
                CapturedStore.attributionOf(tmp));
            assertThat(domain(store.dsl(), CapturedStore.GRAPH)).isEmpty();
            assertThat(store.dsl().fetchCount(GRAPHQL_TYPE,
                GRAPHQL_TYPE.GRAPH_NAME.eq(CapturedStore.GRAPH)))
                .as("the per-site declaration facts are not what an assembly refusal costs")
                .isPositive();
        }
    }

    // ===== Helpers =====

    private static Set<String> domain(DSLContext dsl, String graphName) {
        return new LinkedHashSet<>(dsl.select(INTENT_TYPE_DOMAIN.TYPE_NAME)
            .from(INTENT_TYPE_DOMAIN)
            .where(INTENT_TYPE_DOMAIN.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_TYPE_DOMAIN.TYPE_NAME)
            .fetch(0, String.class));
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
