package no.sikt.graphitron.rewrite.generators.schema;

import com.apollographql.federation.graphqljava.Federation;
import com.apollographql.federation.graphqljava.printer.ServiceSDLPrinter;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaPrinter;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static graphql.Scalars.GraphQLString;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct dispatch coverage on tiny hand-built schemas. The federation arm
 * must produce exactly what {@link ServiceSDLPrinter#generateServiceSDLV2}
 * yields; the non-federation arm must produce what graphql-java's
 * {@link SchemaPrinter} yields under the documented include flags. The
 * pipeline-tier coverage in {@code graphitron-sakila-example} exercises
 * the same dispatch end-to-end with real federation assertions.
 */
@UnitTier
class SchemaSdlEmitterTest {

    private static GraphQLSchema sampleSchema() {
        return GraphQLSchema.newSchema()
            .query(GraphQLObjectType.newObject()
                .name("Query")
                .field(f -> f.name("hello").type(GraphQLString)))
            .build();
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

    @Test
    void federationArmRoutesThroughServiceSdlPrinter(@TempDir Path root) throws IOException {
        GraphQLSchema schema = sampleSchema();
        Path target = SchemaSdlEmitter.emit(schema, true, root, "com.example.app");

        // The emitter runs Federation.transform before printing so the file
        // matches what the consumer's runtime serves; ServiceSDLPrinter then
        // strips the federation runtime types back out.
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
        Path target = SchemaSdlEmitter.emit(schema, false, root, "com.example.app");

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
        Path target = SchemaSdlEmitter.emit(sampleSchema(), false, root, "");

        assertThat(target).isEqualTo(root.resolve("schema.graphqls"));
        assertThat(target).exists();
    }

    /**
     * R283: the new behavior. {@code generateServiceSDLV2} emits the {@code @oneOf}
     * application but strips the spec-built-in definition; the federation arm reinstates it.
     */
    @Test
    void federationArmEmitsOneOfDefinitionWhenSchemaUsesOneOf(@TempDir Path root) throws IOException {
        Path target = SchemaSdlEmitter.emit(oneOfSchema(), true, root, "com.example.app");
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
     * {@link #federationArmRoutesThroughServiceSdlPrinter} pins byte-stability more strongly;
     * this names the invariant explicitly.)
     */
    @Test
    void federationArmOmitsOneOfDefinitionWhenSchemaHasNoOneOf(@TempDir Path root) throws IOException {
        Path target = SchemaSdlEmitter.emit(sampleSchema(), true, root, "com.example.app");

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
        Path target = SchemaSdlEmitter.emit(oneOfSchema(), false, root, "com.example.app");

        assertThat(Files.readString(target, StandardCharsets.UTF_8))
            .as("graphql-java's SchemaPrinter already emits the @oneOf definition on the plain arm")
            .contains("directive @oneOf on INPUT_OBJECT");
    }
}
