package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Federation {@code @tag} inheritance on the walk: an explicit {@code @tag} on an
 * {@code @asConnection} carrier reaches the Connection / Edge / PageInfo types the walk synthesises,
 * which the schema classes are still rendered from. The store's inheritance, configured tags
 * included, is pinned through {@code generate} by {@code StoreEmittedFederationSchemaPipelineTest}.
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
        assertThat(tagNames(obj(assembled, "QueryFilmsConnectionEdge"))).containsExactly("x");
        assertThat(tagNames(obj(assembled, "PageInfo"))).containsExactly("x");
        // The carrier field still carries its own @tag (promotion does not strip it).
        var carrier = ((GraphQLObjectType) assembled.getType("Query")).getFieldDefinition("films");
        assertThat(carrier.getAppliedDirectives("tag")).hasSize(1);
    }

    private static GraphQLObjectType obj(GraphQLSchema schema, String name) {
        return (GraphQLObjectType) schema.getType(name);
    }

    private static List<String> tagNames(GraphQLObjectType type) {
        return type.getAppliedDirectives("tag").stream()
            .map(d -> (String) d.getArgument("name").getValue())
            .toList();
    }
}
