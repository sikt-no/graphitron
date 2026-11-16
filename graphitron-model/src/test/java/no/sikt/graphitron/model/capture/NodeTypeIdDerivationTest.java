package no.sikt.graphitron.model.capture;

import graphql.language.AstPrinter;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
import no.sikt.graphitron.model.schema.EmittedRegistry;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.model.test.TestRunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * What {@code EmittedRegistry} makes of a node type's wire id as {@code @nodeType}. The rows it
 * applies, one per node type of a graph composing the directive, are stated by the fact documents
 * {@code scenarios/node-type-directive} and its variants, which also state that nothing else composes it;
 * the cases here read those graphs and derive the registry the generator publishes from them.
 */
class NodeTypeIdDerivationTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String COMPOSING = "scenarios/node-type-directive";

    @Test
    @DisplayName("the emitted registry applies @nodeType to each row and defines it once")
    void theEmittedRegistryAppliesTheRows() {
        FACTS.read(COMPOSING, graph -> {
            var emitted = EmittedRegistry.derive(registryOf(graph), graph.handle()).registry();

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
            return null;
        });
    }

    @Test
    @DisplayName("a graph that does not compose it publishes exactly what it did before")
    void aGraphNotComposingItIsUntouched() {
        FACTS.read("scenarios/node-type-directive-not-composed", graph -> {
            var emitted = EmittedRegistry.derive(registryOf(graph), graph.handle()).registry();

            assertThat(emitted.getDirectiveDefinition("nodeType")).isEmpty();
            assertThat(directivesOf(emitted, "Film")).noneMatch(d -> d.startsWith("@nodeType"));
            return null;
        });
    }

    /**
     * The emitted registry is derived while capture builds the schemas, so an authored declaration
     * is refused there, as the author's error and before assembly can turn it into a failure
     * reported as a defect in the generator. A capture that fails, so on a store of its own.
     */
    @Test
    @DisplayName("an authored declaration of @nodeType is refused as the author's error")
    void anAuthoredDeclarationIsRefused(@TempDir Path tmp) {
        String sdl = """
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", \
            import: ["@key", "@composeDirective"]) @composeDirective(name: "@nodeType")
            directive @nodeType(typeId: String!) on OBJECT

            type Query { film: Film }

            interface Node { id: ID! }

            type Film implements Node @table(name: "film") @node(typeId: "F") {
              id: ID!
            }
            """;

        var thrown = catchThrowableOfType(ValidationFailedException.class, () ->
            CapturedStore.ownStoreOfCatalog(tmp, sdl, CatalogInput.of(TestRunContext.JOOQ_PACKAGE)));

        assertThat(thrown).as("refused as the author's error, not a generator defect").isNotNull();
        assertThat(thrown.errors().stream().map(e -> e.message()).reduce("", String::concat))
            .contains("@nodeType").contains("is declared here");
    }

    /**
     * An application the author wrote is refused where the emitted registry is derived. Applied to a
     * copy of the document's registry, the population's graph being shared by every reader.
     */
    @Test
    @DisplayName("an authored @nodeType application on a node type is refused as the author's error")
    void anAuthoredApplicationIsRefused() {
        String refusal = FACTS.read(COMPOSING, graph -> {
            var registry = new TypeDefinitionRegistry().merge(registryOf(graph));
            var film = (ObjectTypeDefinition) registry.getTypeOrNull("Film");
            registry.remove(film);
            registry.add(film.transform(b -> b.directive(
                graphql.language.Directive.newDirective().name("nodeType").build())));

            var thrown = catchThrowableOfType(ValidationFailedException.class,
                () -> EmittedRegistry.derive(registry, graph.handle()));
            assertThat(thrown).as("refused as the author's error, not a generator defect").isNotNull();
            return thrown.errors().stream().map(e -> e.message()).reduce("", String::concat);
        });
        assertThat(refusal).contains("@nodeType").contains("'Film'");
    }

    /** The registry the document's files read to, which the generator derives the emitted one from. */
    private static TypeDefinitionRegistry registryOf(FactGraphs.FactGraph graph) {
        return graph.schema().written().read().registry();
    }

    private static List<String> directivesOf(TypeDefinitionRegistry registry, String type) {
        return ((ObjectTypeDefinition) registry.getTypeOrNull(type)).getDirectives().stream()
            .map(AstPrinter::printAst).toList();
    }
}
