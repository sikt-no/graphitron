package no.sikt.graphql.helpers.resolvers;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.Coercing;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLScalarType;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import no.sikt.graphql.DefaultGraphitronContext;
import no.sikt.graphql.NodeIdStrategy;
import org.dataloader.DataLoaderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Node IDs are emitted as unpadded base64url, but the decoder also accepts other encodings of the same bytes. The
 * generated node and entity queries find the row for such an ID, and key the result by the canonical ID. These tests
 * check that the result is still matched back to the ID the client sent. No {@link NodeIdStrategy} is passed, as in
 * NodeIdHandler mode.
 */
class NonCanonicalNodeIdTest {
    // Decodes to "6:1234,201".
    private static final String CANONICAL_ID = "NjoxMjM0LDIwMQ";
    private static final String SCHEMA = """
            scalar _Any
            type Query {
              node(id: ID!): Thing
              _entities(representations: [_Any!]!): [Thing]!
            }
            type Thing { id: ID }
            """;

    private static final GraphQLScalarType ANY = GraphQLScalarType.newScalar()
            .name("_Any")
            .coercing(new Coercing<Object, Object>() {
                @Override
                public Object serialize(Object dataFetcherResult) {
                    return dataFetcherResult;
                }

                @Override
                public Object parseValue(Object input) {
                    return input;
                }
            })
            .build();

    /**
     * Stands in for a generated {@code <Type>ForNode} query: filters by decoded ID, keys the result by the re-encoded ID.
     */
    private static Map<String, Map<String, Object>> thingForNode(java.util.Set<String> ids) {
        return ids.stream()
                .filter(it -> Objects.equals(NodeIdStrategy.decodeAsNodeId(it), NodeIdStrategy.decodeAsNodeId(CANONICAL_ID)))
                .map(it -> CANONICAL_ID)
                .distinct()
                .collect(Collectors.toMap(it -> it, it -> Map.of("id", it)));
    }

    /**
     * Stands in for a generated {@code <Type>For_Entity} query: the result is keyed by representations re-built from the row.
     */
    private static Map<Map<String, Object>, Object> thingForEntity(java.util.Set<Map<String, Object>> representations) {
        var result = new HashMap<Map<String, Object>, Object>();
        thingForNode(representations.stream().map(it -> (String) it.get("id")).collect(Collectors.toSet()))
                .forEach((id, thing) -> result.put(Map.of("__typename", "Thing", "id", id), thing));
        return result;
    }

    private static final List<String> ENCODINGS_OF_SAME_ID = List.of(
            CANONICAL_ID,
            CANONICAL_ID + "==",     // base64 padding
            "NjoxMjM0LDIwMR",        // non-zero trailing bits, decodes to the same bytes
            "NjoxMjM0LDIwMR=="
    );
    // "6:1234,202", a row that does not exist.
    private static final List<String> ENCODINGS_OF_OTHER_ID = List.of("NjoxMjM0LDIwMg", "NjoxMjM0LDIwMg==");

    @Test
    @DisplayName("node finds the row for any encoding of its ID")
    void nodeMatchesNonCanonicalId() {
        for (var requestedId : ENCODINGS_OF_SAME_ID) {
            assertThat(node(requestedId)).as(requestedId).isEqualTo(Map.of("id", CANONICAL_ID));
        }
    }

    @Test
    @DisplayName("_entities finds the entity for any encoding of its ID")
    void entitiesMatchesNonCanonicalId() {
        for (var requestedId : ENCODINGS_OF_SAME_ID) {
            assertThat(entities(requestedId)).as(requestedId).isEqualTo(List.of(Map.of("id", CANONICAL_ID)));
        }
    }

    @Test
    @DisplayName("node and _entities still return null for an ID of another row")
    void otherIdIsNotMatched() {
        for (var requestedId : ENCODINGS_OF_OTHER_ID) {
            assertThat(node(requestedId)).as(requestedId).isNull();
            assertThat(entities(requestedId)).as(requestedId).containsExactly((Object) null);
        }
    }

    private static Object node(String id) {
        return execute("query($id: ID!) { node(id: $id) { id } }", Map.of("id", id)).get("node");
    }

    private static List<Object> entities(String id) {
        return (List<Object>) execute(
                "query($reps: [_Any!]!) { _entities(representations: $reps) { id } }",
                Map.of("reps", List.of(Map.of("__typename", "Thing", "id", id)))
        ).get("_entities");
    }

    private static Map<String, Object> execute(String query, Map<String, Object> variables) {
        DataFetcher<?> nodeFetcher = env -> new DataFetcherHelper(env).<String, Object, Map<String, Object>>loadInterface(
                "Thing_node",
                env.getArgument("id"),
                (ctx, ids, selectionSet) -> thingForNode(ids)
        );
        DataFetcher<?> entitiesFetcher = env -> new DataFetcherHelper(env).loadLookupEntities(
                env.getArgument("representations"),
                Map.of("Thing", (ctx, reps, selectionSet) -> thingForEntity(reps))
        );

        var wiring = RuntimeWiring.newRuntimeWiring()
                .scalar(ANY)
                .type("Query", it -> it.dataFetcher("node", nodeFetcher).dataFetcher("_entities", entitiesFetcher))
                .build();
        var schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SCHEMA), wiring);

        var result = GraphQL.newGraphQL(schema).build().execute(
                ExecutionInput.newExecutionInput()
                        .query(query)
                        .variables(variables)
                        .graphQLContext(Map.of("graphitronContext", new DefaultGraphitronContext(null)))
                        .dataLoaderRegistry(new DataLoaderRegistry())
                        .build()
        );

        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
