package no.sikt.graphitron.rewrite.generators.schema;

import com.apollographql.federation.graphqljava.Federation;
import com.apollographql.federation.graphqljava.printer.ServiceSDLPrinter;
import graphql.language.EnumTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaPrinter;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.schema.DirectiveSupportTypes;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static graphql.Scalars.GraphQLString;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct dispatch coverage on tiny schemas. The federation arm mirrors the option shape of
 * {@link ServiceSDLPrinter#generateServiceSDLV2} plus the R291 survivor/support-type filters,
 * so on a schema with no generator-only surface it must produce exactly what that printer
 * yields; the non-federation arm mirrors graphql-java's {@link SchemaPrinter} under the
 * documented include flags the same way. SDL assertions on the filtered surface re-parse the
 * printed text and assert on the resulting type/directive sets, never on substrings of the
 * printed string. The execution-tier coverage in {@code graphitron-sakila-example}
 * ({@code FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema}) pins file ↔ runtime parity
 * end-to-end.
 */
@UnitTier
class SchemaSdlEmitterTest {

    /** Schema exercising the R291 filtered surface: generator-only directives, a consumer
     * survivor directive, and a retained published support type. */
    private static final String FILTERED_SDL = """
        directive @auth(roles: [String!]) on OBJECT | FIELD_DEFINITION
        type Query {
            films(order: [FilmOrderBy]): Film @auth(roles: ["admin"])
        }
        type Film @table(name: "film") {
            id: ID!
        }
        input FilmOrderBy { direction: SortDirection }
        """;

    /** Same shape without the support-type reference: every support type drops. */
    private static final String UNREFERENCED_SDL = """
        type Query { film: Film }
        type Film @table(name: "film") { id: ID! }
        """;

    private static GraphQLSchema sampleSchema() {
        return GraphQLSchema.newSchema()
            .query(GraphQLObjectType.newObject()
                .name("Query")
                .field(f -> f.name("hello").type(GraphQLString)))
            .build();
    }

    /** Empty classified model: no support type is retained, nothing else is consulted. */
    private static GraphitronSchema emptyModel() {
        return new GraphitronSchema(Map.of(), Map.of());
    }

    /**
     * A schema whose {@code Filter} input applies the built-in {@code @oneOf} directive.
     * Built through graphql-java's SchemaGenerator (as the real pipeline does) so the input
     * object reports {@link graphql.schema.GraphQLInputObjectType#isOneOf()}.
     */
    private static GraphQLSchema oneOfSchema() {
        String sdl = "type Query { search(filter: Filter): String }\n"
            + "input Filter @oneOf { byId: ID byName: String }\n";
        var registry = new graphql.schema.idl.SchemaParser().parse(sdl);
        return graphql.schema.idl.UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
    }

    private static TypeDefinitionRegistry emitAndReparse(GraphitronSchemaBuilder.Bundle bundle,
                                                         boolean federationLink, Path root) throws IOException {
        Path target = SchemaSdlEmitter.emit(bundle.assembled(), bundle.model(), federationLink, root, "com.example.app");
        return new graphql.schema.idl.SchemaParser().parse(Files.readString(target, StandardCharsets.UTF_8));
    }

    /**
     * On a schema with no generator-only directives and no support types, the federation arm's
     * printer must be byte-identical to {@link ServiceSDLPrinter#generateServiceSDLV2}: the
     * R291 filters are pure subtractions, and this pins the mirrored option shape.
     */
    @Test
    void federationArmMatchesServiceSdlPrinterOnUnfilteredSchema(@TempDir Path root) throws IOException {
        GraphQLSchema schema = sampleSchema();
        Path target = SchemaSdlEmitter.emit(schema, emptyModel(), true, root, "com.example.app");

        var expected = ServiceSDLPrinter.generateServiceSDLV2(
            Federation.transform(schema)
                .setFederation2(true)
                .resolveEntityType(env -> null)
                .fetchEntities(env -> java.util.List.of())
                .build());
        assertThat(target).isEqualTo(root.resolve("com/example/app/schema.graphqls"));
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(expected);
    }

    @Test
    void nonFederationArmRoutesThroughSchemaPrinter(@TempDir Path root) throws IOException {
        GraphQLSchema schema = sampleSchema();
        Path target = SchemaSdlEmitter.emit(schema, emptyModel(), false, root, "com.example.app");

        var expected = new SchemaPrinter(SchemaPrinter.Options.defaultOptions()
            .includeDirectives(true)
            .includeScalarTypes(true)
            .includeIntrospectionTypes(false)
            .includeSchemaDefinition(true)).print(schema);
        assertThat(target).isEqualTo(root.resolve("com/example/app/schema.graphqls"));
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(expected);
    }

    @Test
    void emptyOutputPackageWritesAtResourcesRoot(@TempDir Path root) throws IOException {
        Path target = SchemaSdlEmitter.emit(sampleSchema(), emptyModel(), false, root, "");

        assertThat(target).isEqualTo(root.resolve("schema.graphqls"));
        assertThat(target).exists();
    }

    /**
 * Generator-only directive definitions and applications never print; consumer-declared
     * survivor directives keep printing with their applications. Asserted on both arms.
     */
    @Test
    void bothArmsStripGeneratorOnlyDirectivesAndKeepSurvivors(@TempDir Path root) throws IOException {
        var bundle = TestSchemaHelper.buildBundle(FILTERED_SDL);
        for (boolean federationLink : new boolean[] {false, true}) {
            var reparsed = emitAndReparse(bundle, federationLink, root.resolve(federationLink ? "fed" : "plain"));

            assertThat(reparsed.getDirectiveDefinition("table"))
                .as("generator-only directive definition must not print (federation=%s)", federationLink)
                .isEmpty();
            assertThat(reparsed.getDirectiveDefinition("auth"))
                .as("consumer-declared survivor directive definition must print (federation=%s)", federationLink)
                .isPresent();

            var film = requireNonNull(reparsed.getTypeOrNull("Film", ObjectTypeDefinition.class));
            assertThat(film.getDirectives("table"))
                .as("generator-only directive application must not print (federation=%s)", federationLink)
                .isEmpty();
            var query = requireNonNull(reparsed.getTypeOrNull("Query", ObjectTypeDefinition.class));
            var films = query.getFieldDefinitions().stream()
                .filter(f -> f.getName().equals("films"))
                .findFirst().orElseThrow();
            assertThat(films.getDirectives("auth"))
                .as("survivor directive application must print (federation=%s)", federationLink)
                .hasSize(1);
        }
    }

    /**
 * Strictly internal support types never print; the published support type prints
     * iff classification retained it, with its description. Asserted on both arms.
     */
    @Test
    void bothArmsDropNonRetainedSupportTypesAndKeepRetainedOnes(@TempDir Path root) throws IOException {
        var referenced = TestSchemaHelper.buildBundle(FILTERED_SDL);
        var unreferenced = TestSchemaHelper.buildBundle(UNREFERENCED_SDL);
        for (boolean federationLink : new boolean[] {false, true}) {
            var withReference = emitAndReparse(referenced, federationLink, root.resolve("ref-" + federationLink));
            var withoutReference = emitAndReparse(unreferenced, federationLink, root.resolve("unref-" + federationLink));

            DirectiveSupportTypes.strictlyInternal().forEach(name ->
                assertThat(withReference.getTypeOrNull(name))
                    .as("strictly internal type %s must never print (federation=%s)", name, federationLink)
                    .isNull());

            var sortDirection = requireNonNull(withReference.getTypeOrNull("SortDirection", EnumTypeDefinition.class));
            assertThat(sortDirection.getDescription())
                .as("retained SortDirection carries its description (federation=%s)", federationLink)
                .isNotNull();

            DirectiveSupportTypes.all().forEach(name ->
                assertThat(withoutReference.getTypeOrNull(name))
                    .as("unreferenced support type %s must not print (federation=%s)", name, federationLink)
                    .isNull());
        }
    }

    /**
 * {@code generateServiceSDLV2}'s spec-built-in filter (now mirrored in the emitter's
     * own printer) strips the {@code @oneOf} definition while keeping the application; the
     * federation arm reinstates the definition.
     */
    @Test
    void federationArmEmitsOneOfDefinitionWhenSchemaUsesOneOf(@TempDir Path root) throws IOException {
        Path target = SchemaSdlEmitter.emit(oneOfSchema(), emptyModel(), true, root, "com.example.app");
        String sdl = Files.readString(target, StandardCharsets.UTF_8);

        assertThat(sdl)
            .as("federation arm must reinstate the @oneOf directive definition")
            .contains("directive @oneOf on INPUT_OBJECT")
            .as("the @oneOf application must remain on the input type")
            .containsPattern("input\\s+Filter\\b[^{]*@oneOf");
    }

    /**
     * R283 no-op / byte-stability guard: a schema that never uses {@code @oneOf} must not gain
     * the definition. (The exact-equality assertion in
     * {@link #federationArmMatchesServiceSdlPrinterOnUnfilteredSchema} pins byte-stability more
     * strongly; this names the invariant explicitly.)
     */
    @Test
    void federationArmOmitsOneOfDefinitionWhenSchemaHasNoOneOf(@TempDir Path root) throws IOException {
        Path target = SchemaSdlEmitter.emit(sampleSchema(), emptyModel(), true, root, "com.example.app");

        assertThat(Files.readString(target, StandardCharsets.UTF_8))
            .as("schemas that never use @oneOf keep byte-identical federation output")
            .doesNotContain("directive @oneOf");
    }

    /**
     * Regression guard on existing graphql-java 25.0 behavior, NOT coverage of R283's fix:
     * the non-federation {@link SchemaPrinter} already prints spec-built-in directive
     * definitions, so the plain arm emits {@code @oneOf} without any graphitron augmentation.
     */
    @Test
    void plainArmEmitsOneOfDefinitionUnchanged(@TempDir Path root) throws IOException {
        Path target = SchemaSdlEmitter.emit(oneOfSchema(), emptyModel(), false, root, "com.example.app");

        assertThat(Files.readString(target, StandardCharsets.UTF_8))
            .as("graphql-java's SchemaPrinter already emits the @oneOf definition on the plain arm")
            .contains("directive @oneOf on INPUT_OBJECT");
    }
}
