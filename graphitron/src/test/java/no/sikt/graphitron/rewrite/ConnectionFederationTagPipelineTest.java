package no.sikt.graphitron.rewrite;

import graphql.language.ObjectTypeDefinition;
import graphql.language.StringValue;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.generators.schema.SchemaSdlEmitter;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of federation {@code @tag} inheritance: the federation {@code @tag} on an {@code @asConnection} carrier
 * field must reach the synthesised Connection / Edge / PageInfo types on the assembled schema
 * (the federation-SDL emission source) and survive the federation SDL round-trip.
 *
 * <p>Exercises both tag sources. The explicit arm writes {@code @tag} directly in the SDL and
 * asserts on the assembled schema. The {@code <schemaInput tag>} arm drives the tag through
 * {@code TagApplier} via the {@code loadAttributedRegistry()} hook in the
 * {@code TaggedInputsPipelineTest} shape; because that path also synthesises the federation
 * {@code @link}, its built schema is federation-shaped and feeds the emission round-trip
 * (the {@code FederationBuildSmokeTest} shape, through {@link SchemaSdlEmitter}).
 */
@PipelineTier
class ConnectionFederationTagPipelineTest {

    @Test
    void explicitTag_assembledSynthesisedTypesCarryTag() {
        String sdl = """
            directive @tag(name: String!) repeatable on FIELD_DEFINITION | OBJECT
            type Film @table(name: "film") { id: ID }
            type Query {
                films: [Film!]! @asConnection @defaultOrder(primaryKey: true) @tag(name: "x")
            }
            """;
        GraphQLSchema assembled = TestSchemaHelper.buildBundle(sdl).assembled();

        assertThat(tagNames(obj(assembled, "QueryFilmsConnection"))).containsExactly("x");
        assertThat(tagNames(obj(assembled, "QueryFilmsEdge"))).containsExactly("x");
        assertThat(tagNames(obj(assembled, "PageInfo"))).containsExactly("x");
        // The carrier field still carries its own @tag (promotion does not strip it).
        var carrier = ((GraphQLObjectType) assembled.getType("Query")).getFieldDefinition("films");
        assertThat(carrier.getAppliedDirectives("tag")).hasSize(1);
    }

    @Test
    void schemaInputTag_synthesisedTypesInheritCarrierTag(@TempDir Path tmp) throws IOException {
        GraphQLSchema assembled = buildSchemaInputTaggedBundle(tmp).assembled();

        // TagApplier stamps @tag(name: "catalog") on the carrier field; promotion inherits it.
        assertThat(tagNames(obj(assembled, "QueryFilmsConnection"))).containsExactly("catalog");
        assertThat(tagNames(obj(assembled, "QueryFilmsEdge"))).containsExactly("catalog");
        assertThat(tagNames(obj(assembled, "PageInfo"))).containsExactly("catalog");
    }

    @Test
    void schemaInputTag_federationSdlRoundTripCarriesTagOnSynthesisedTypes(@TempDir Path tmp) throws IOException {
        var bundle = buildSchemaInputTaggedBundle(tmp);
        assertThat(bundle.federationLink())
            .as("<schemaInput tag> synthesises the federation @link, so emission takes the federation arm")
            .isTrue();

        Path target = SchemaSdlEmitter.emit(
            bundle.assembled(), bundle.model(), bundle.federationLink(), tmp, "com.example.app");
        TypeDefinitionRegistry reparsed =
            new SchemaParser().parse(Files.readString(target, StandardCharsets.UTF_8));

        assertThat(reparsedTagNames(reparsed, "QueryFilmsConnection")).containsExactly("catalog");
        assertThat(reparsedTagNames(reparsed, "QueryFilmsEdge")).containsExactly("catalog");
        assertThat(reparsedTagNames(reparsed, "PageInfo")).containsExactly("catalog");
    }

    private static GraphitronSchemaBuilder.Bundle buildSchemaInputTaggedBundle(Path tmp) throws IOException {
        Path src = tmp.resolve("catalog.graphqls");
        Files.writeString(src, """
            type Film @table(name: "film") { id: ID }
            type Query {
                films: [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);
        var ctx = new RewriteContext(
            List.of(new SchemaInput(src.toString(), Optional.of("catalog"), Optional.empty())),
            tmp, tmp, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE, Map.of());
        var registry = new GraphQLRewriteGenerator(ctx).loadAttributedRegistry();
        return GraphitronSchemaBuilder.buildBundle(registry, ctx);
    }

    private static GraphQLObjectType obj(GraphQLSchema schema, String name) {
        return (GraphQLObjectType) schema.getType(name);
    }

    private static List<String> tagNames(GraphQLObjectType type) {
        return type.getAppliedDirectives("tag").stream()
            .map(d -> (String) d.getArgument("name").getValue())
            .toList();
    }

    private static List<String> reparsedTagNames(TypeDefinitionRegistry reg, String typeName) {
        var def = requireNonNull(reg.getTypeOrNull(typeName, ObjectTypeDefinition.class));
        return def.getDirectives("tag").stream()
            .map(d -> ((StringValue) d.getArgument("name").getValue()).getValue())
            .toList();
    }
}
