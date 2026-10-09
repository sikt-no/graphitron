package no.sikt.graphitron.rewrite.capture;

import graphql.language.AstPrinter;
import graphql.language.ObjectTypeDefinition;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.schema.EmittedRegistry;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_SYNTHESIZED_NODE_TYPE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A node type's wire id as {@code @nodeType}, over a real capture: a graph that composes the
 * directive gets one row per node type from {@code graphitron_synthesized_node_type_id}, and
 * {@code EmittedRegistry} applies what the rows say.
 *
 * <p>Composing the directive is the only opt-in, so what each case varies is what the graph writes
 * about it. The id each row carries is {@code graphitron_node.type_id} as the store resolved it, so
 * the cases that pin the three tiers do so over a real catalog: one type pins its id, one is named
 * by the class its table publishes, one has neither.
 */
@UnitTier
class NodeTypeIdDerivationTest {

    private static final String LINK =
        "extend schema @link(url: \"https://specs.apollo.dev/federation/v2.10\", import: [\"@key\", \"@composeDirective\"])";

    private static final String COMPOSING = LINK + " @composeDirective(name: \"@nodeType\")\n";

    /**
     * Three node types over three id tiers. {@code Pairing} names no id and has no {@code @node}: it
     * is a node because it implements {@code Node} over {@code film_actor}, which publishes the
     * node-identity constants, and the id is the one that class states rather than the type's name.
     */
    private static final String NODES = """

        type Query { film: Film }

        interface Node { id: ID! }

        type Film implements Node @table(name: "film") @node(typeId: "F") {
          id: ID!
        }

        type Pairing implements Node @table(name: "film_actor") {
          id: ID!
        }

        type Customer implements Node @table(name: "customer") @node {
          id: ID!
        }
        """;

    @Test
    @DisplayName("a graph composing @nodeType gets one row per node type, carrying the id as resolved")
    void eachNodeTypeCarriesItsResolvedId(@TempDir Path tmp) {
        try (var store = captured(tmp, COMPOSING + NODES)) {
            assertThat(rows(store))
                .as("declared, class-published and type-name tiers, in that order of precedence")
                .containsExactlyInAnyOrder(tuple("Film", "F"), tuple("Pairing", "FilmActor"),
                    tuple("Customer", "Customer"));
        }
    }

    @Test
    @DisplayName("a graph that does not compose @nodeType has no rows")
    void composingNothingMeansNoRows(@TempDir Path tmp) {
        try (var store = captured(tmp, LINK + NODES)) {
            assertThat(rows(store)).isEmpty();
        }
    }

    @Test
    @DisplayName("composing some other directive is not composing @nodeType")
    void composingAnotherDirectiveMeansNoRows(@TempDir Path tmp) {
        try (var store = captured(tmp,
                LINK + " @composeDirective(name: \"@somethingElse\")\n" + NODES)) {
            assertThat(rows(store)).isEmpty();
        }
    }

    @Test
    @DisplayName("the namespaced spelling composes it too")
    void theNamespacedSpellingComposesIt(@TempDir Path tmp) {
        String link = "extend schema @link(url: \"https://specs.apollo.dev/federation/v2.10\", import: [\"@key\"])"
            + " @federation__composeDirective(name: \"@nodeType\")\n";
        try (var store = captured(tmp, link + NODES)) {
            assertThat(rows(store)).hasSize(3);
        }
    }

    /**
     * {@code @node} only takes effect over {@code @table}, so a node with no table has no row in the
     * resolved node relation and so none here. Both producers say nothing for it, and the author is
     * told by the anti-join that reports the shape.
     */
    @Test
    @DisplayName("a @node with no @table has no row")
    void aNodeWithNoTableHasNoRow(@TempDir Path tmp) {
        String sdl = COMPOSING + """

            type Query { ghost: Ghost }

            interface Node { id: ID! }

            type Ghost implements Node @node(typeId: "G") {
              id: ID!
            }
            """;
        try (var store = captured(tmp, sdl)) {
            assertThat(rows(store)).isEmpty();
        }
    }

    @Test
    @DisplayName("the emitted registry applies @nodeType to each row and defines it once")
    void theEmittedRegistryAppliesTheRows(@TempDir Path tmp) {
        try (var store = captured(tmp, COMPOSING + NODES)) {
            var emitted = EmittedRegistry.derive(store.registry(), handle(store)).registry();

            assertThat(directivesOf(emitted, "Film")).contains("@nodeType(typeId: \"F\")");
            assertThat(directivesOf(emitted, "Pairing")).contains("@nodeType(typeId: \"FilmActor\")");
            assertThat(directivesOf(emitted, "Customer")).contains("@nodeType(typeId: \"Customer\")");
            assertThat(emitted.getDirectiveDefinition("nodeType"))
                .as("the definition arrives with the applications, federation-jvm knowing nothing of it")
                .isPresent();
            assertThat(SchemaAssembly.of(emitted).errors())
                .as("this registry is the transcribed one, without the federation definitions the"
                    + " loading rewrites inject, so assembly has complaints of its own; none is"
                    + " about @nodeType, which is the claim")
                .noneMatch(e -> e.message().contains("nodeType"));
        }
    }

    @Test
    @DisplayName("a graph that does not compose it publishes exactly what it did before")
    void aGraphNotComposingItIsUntouched(@TempDir Path tmp) {
        try (var store = captured(tmp, LINK + NODES)) {
            var emitted = EmittedRegistry.derive(store.registry(), handle(store)).registry();

            assertThat(emitted.getDirectiveDefinition("nodeType")).isEmpty();
            assertThat(directivesOf(emitted, "Film")).noneMatch(d -> d.startsWith("@nodeType"));
        }
    }

    /**
     * The emitted registry is derived while capture builds the schemas, so an authored declaration
     * is refused there, as the author's error and before assembly can turn it into a failure
     * reported as a defect in the generator.
     */
    @Test
    @DisplayName("an authored declaration of @nodeType is refused as the author's error")
    void anAuthoredDeclarationIsRefused(@TempDir Path tmp) {
        String sdl = COMPOSING + "directive @nodeType(typeId: String!) on OBJECT\n" + NODES;

        var thrown = catchThrowableOfType(ValidationFailedException.class, () -> captured(tmp, sdl));

        assertThat(thrown).as("refused as the author's error, not a generator defect").isNotNull();
        assertThat(thrown.errors().stream().map(e -> e.message()).reduce("", String::concat))
            .contains("@nodeType").contains("is declared here");
    }

    @Test
    @DisplayName("an authored @nodeType application on a node type is refused as the author's error")
    void anAuthoredApplicationIsRefused(@TempDir Path tmp) {
        try (var store = captured(tmp, COMPOSING + NODES)) {
            var registry = store.registry();
            var film = (ObjectTypeDefinition) registry.getTypeOrNull("Film");
            registry.remove(film);
            registry.add(film.transform(b -> b.directive(
                graphql.language.Directive.newDirective().name("nodeType").build())));

            assertThat(refusalOf(store, registry))
                .contains("@nodeType").contains("'Film'");
        }
    }

    // ---------------------------------------------------------------------------------------

    private static CapturedStore captured(Path tmp, String sdl) {
        return CapturedStore.ofCatalog(tmp, sdl, new JooqCatalog(TestConfiguration.DEFAULT_JOOQ_PACKAGE));
    }

    /** The one author-facing message the emitted registry refuses with, in the author's terms. */
    private static String refusalOf(CapturedStore store, graphql.schema.idl.TypeDefinitionRegistry registry) {
        var thrown = catchThrowableOfType(ValidationFailedException.class,
            () -> EmittedRegistry.derive(registry, handle(store)));
        assertThat(thrown).as("refused as the author's error, not a generator defect").isNotNull();
        return thrown.errors().stream().map(e -> e.message()).reduce("", String::concat);
    }

    private static StoreHandle handle(CapturedStore store) {
        return new StoreHandle(store.dsl(), CapturedStore.GRAPH);
    }

    private static List<org.assertj.core.groups.Tuple> rows(CapturedStore store) {
        var t = GRAPHITRON_SYNTHESIZED_NODE_TYPE_ID;
        return store.dsl().select(t.TYPE_NAME, t.TYPE_ID).from(t)
            .fetch(r -> tuple(r.value1(), r.value2()));
    }

    private static List<String> directivesOf(graphql.schema.idl.TypeDefinitionRegistry registry, String type) {
        return ((ObjectTypeDefinition) registry.getTypeOrNull(type)).getDirectives().stream()
            .map(AstPrinter::printAst).toList();
    }
}
