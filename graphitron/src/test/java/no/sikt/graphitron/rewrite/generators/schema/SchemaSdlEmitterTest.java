package no.sikt.graphitron.rewrite.generators.schema;

import com.apollographql.federation.graphqljava.Federation;
import com.apollographql.federation.graphqljava.printer.ServiceSDLPrinter;
import graphql.language.AstPrinter;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.SchemaPrinter;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import no.sikt.graphitron.model.schema.federation.FederationSpec;
import no.sikt.graphitron.model.schema.input.FederationLinkApplier;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct dispatch coverage on tiny schemas. The printer takes the emitted schema and prints it,
 * filtering nothing of its own; which form it prints follows the schema's own federation
 * {@code @link}. The federation form mirrors the option shape of
 * {@link ServiceSDLPrinter#generateServiceSDLV2}, so it must state what that printer states; the
 * plain form mirrors graphql-java's {@link SchemaPrinter} the same way. Every assertion re-parses the
 * printed text and asserts on the definitions it holds, never on the printed string: the file is
 * what a consumer publishes, and its layout is the printer's. What is published is decided before
 * this, in {@code EmittedRegistry}, and pinned there; the execution tier in
 * {@code graphitron-sakila-example} ({@code FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema})
 * pins file and runtime parity end to end.
 */
@UnitTier
class SchemaSdlEmitterTest {

    private static final String LINK = "extend schema @link(url: \"" + FederationSpec.URL
        + "\", import: [\"@key\"])\n";

    private static final String FILM = """
        directive @table(name: String) on OBJECT
        type Query { film: Film }
        type Film @table(name: "film") { id: ID! }
        enum SortDirection { ASC DESC }
        """;

    /** A schema built from {@code sdl}, its federation definitions injected where it links them. */
    private static GraphQLSchema schema(String sdl) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(sdl);
        FederationLinkApplier.apply(registry);
        return UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
    }

    private static final GraphQLSchema PLAIN = schema("type Query { hello: String }");
    private static final GraphQLSchema FEDERATED = schema(LINK + "type Query { hello: String }");

    /**
     * The definitions an SDL text holds, each printed compactly and sorted: two texts with the same
     * definitions compare equal however they are laid out or ordered.
     */
    private static List<String> definitions(String sdl) {
        return new Parser().parseDocument(sdl).getDefinitions().stream()
            .map(AstPrinter::printAstCompact)
            .sorted()
            .toList();
    }

    private static String printed(Path target) throws IOException {
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    @Test
    void aSchemaLinkingFederationPrintsTheServiceSdl(@TempDir Path root) throws IOException {
        Path target = SchemaSdlEmitter.emit(FEDERATED, root, "com.example.app");

        var expected = ServiceSDLPrinter.generateServiceSDLV2(
            Federation.transform(FEDERATED)
                .setFederation2(true)
                .resolveEntityType(env -> null)
                .fetchEntities(env -> List.of())
                .build());
        assertThat(target).isEqualTo(root.resolve("com/example/app/schema.graphqls"));
        assertThat(definitions(printed(target))).isEqualTo(definitions(expected));
    }

    @Test
    void aSchemaWithoutAFederationLinkPrintsPlain(@TempDir Path root) throws IOException {
        Path target = SchemaSdlEmitter.emit(PLAIN, root, "com.example.app");

        var expected = new SchemaPrinter(SchemaPrinter.Options.defaultOptions()
            .includeDirectives(true)
            .includeScalarTypes(true)
            .includeIntrospectionTypes(false)
            .includeSchemaDefinition(true)).print(PLAIN);
        assertThat(definitions(printed(target))).isEqualTo(definitions(expected));
    }

    @Test
    void onlyAFederationLinkSelectsTheServiceForm() {
        assertThat(SchemaSdlEmitter.linksFederation(FEDERATED)).isTrue();
        assertThat(SchemaSdlEmitter.linksFederation(PLAIN)).isFalse();
    }

    @Test
    void emptyOutputPackageWritesAtResourcesRoot(@TempDir Path root) {
        Path target = SchemaSdlEmitter.emit(PLAIN, root, "");

        assertThat(target).isEqualTo(root.resolve("schema.graphqls"));
        assertThat(target).exists();
    }

    /**
     * What the emitted schema holds prints, a directive of any name and a type nothing references
     * included: deciding what is published is {@code EmittedRegistry}'s, and a printer that also
     * filtered would make it two decisions.
     */
    @Test
    void thePrinterFiltersNothing(@TempDir Path root) throws IOException {
        for (boolean federated : new boolean[] {false, true}) {
            var reparsed = new SchemaParser().parse(printed(SchemaSdlEmitter.emit(
                schema(federated ? LINK + FILM : FILM),
                root.resolve(federated ? "fed" : "plain"), "com.example.app")));

            assertThat(reparsed.getDirectiveDefinition("table"))
                .as("the directive definition prints (federation=%s)", federated).isPresent();
            assertThat(requireNonNull(reparsed.getTypeOrNull("Film", ObjectTypeDefinition.class))
                    .getDirectives("table"))
                .as("its application prints (federation=%s)", federated).hasSize(1);
            assertThat(reparsed.getTypeOrNull("SortDirection"))
                .as("an unreferenced type prints (federation=%s)", federated).isNotNull();
        }
    }

    /**
     * {@code generateServiceSDLV2}'s spec-built-in filter (mirrored in the emitter's own printer)
     * strips the {@code @oneOf} definition while keeping the application; the federation form
     * reinstates the definition.
     */
    @Test
    void theFederationFormReinstatesTheOneOfDefinition(@TempDir Path root) throws IOException {
        Path target = SchemaSdlEmitter.emit(schema(LINK
            + "type Query { search(filter: Filter): String }\n"
            + "input Filter @oneOf { byId: ID byName: String }\n"), root, "com.example.app");
        var reparsed = new SchemaParser().parse(printed(target));

        assertThat(reparsed.getDirectiveDefinition("oneOf"))
            .as("the federation form must reinstate the @oneOf directive definition")
            .hasValueSatisfying(oneOf -> assertThat(oneOf.getDirectiveLocations())
                .extracting(location -> location.getName()).containsExactly("INPUT_OBJECT"));
        assertThat(requireNonNull(reparsed.getTypeOrNull("Filter", InputObjectTypeDefinition.class))
                .getDirectives("oneOf"))
            .as("the @oneOf application must remain on the input type")
            .hasSize(1);
    }

    /** A federated schema that never uses {@code @oneOf} gains no definition of it. */
    @Test
    void theFederationFormAddsNoOneOfUnused(@TempDir Path root) throws IOException {
        Path target = SchemaSdlEmitter.emit(FEDERATED, root, "com.example.app");

        assertThat(new SchemaParser().parse(printed(target)).getDirectiveDefinition("oneOf"))
            .isEmpty();
    }

    /**
     * Regression guard on graphql-java 25.0: the plain {@link SchemaPrinter} already prints
     * spec-built-in directive definitions, so the plain form emits {@code @oneOf} without any
     * graphitron augmentation.
     */
    @Test
    void thePlainFormPrintsTheOneOfDefinitionUnchanged(@TempDir Path root) throws IOException {
        Path target = SchemaSdlEmitter.emit(schema("type Query { search(filter: Filter): String }\n"
            + "input Filter @oneOf { byId: ID byName: String }\n"), root, "com.example.app");

        assertThat(new SchemaParser().parse(printed(target)).getDirectiveDefinition("oneOf"))
            .isPresent();
    }
}
