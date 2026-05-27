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
}
