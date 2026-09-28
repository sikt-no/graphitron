package no.sikt.graphql.helpers.resolvers;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetcher;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import no.sikt.graphql.DefaultGraphitronContext;
import org.dataloader.DataLoaderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DataLoaderBatchSizeTest {
    private static final int NUMBER_OF_PARENTS = 7;
    private static final String SCHEMA = """
            type Query { parents: [Parent] }
            type Parent { id: Int, child: Child }
            type Child { id: Int }
            """;

    @Test
    @DisplayName("Loads all keys in a single batch when no max batch size is set")
    void unlimitedBatchSize() {
        var batchSizes = execute(new DefaultGraphitronContext(null));
        assertThat(batchSizes).containsExactly(NUMBER_OF_PARENTS);
    }

    @Test
    @DisplayName("Splits the keys into batches no larger than the max batch size")
    void limitedBatchSize() {
        var batchSizes = execute(new DefaultGraphitronContext(null, 3));
        assertThat(batchSizes).containsExactlyInAnyOrder(3, 3, 1);
    }

    private static List<Integer> execute(DefaultGraphitronContext graphitronContext) {
        var batchSizes = new CopyOnWriteArrayList<Integer>();
        DataFetcher<?> childFetcher = env -> new DataFetcherHelper(env).<Integer, Map<String, Integer>>load(
                ((Map<String, Integer>) env.getSource()).get("id"),
                (ctx, keys, selectionSet) -> {
                    batchSizes.add(keys.size());
                    return keys.stream().collect(Collectors.toMap(Function.identity(), it -> Map.of("id", it)));
                }
        );

        var wiring = RuntimeWiring.newRuntimeWiring()
                .type("Query", it -> it.dataFetcher("parents", env ->
                        IntStream.range(0, NUMBER_OF_PARENTS).mapToObj(id -> Map.of("id", id)).toList()))
                .type("Parent", it -> it.dataFetcher("child", childFetcher))
                .build();
        var schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SCHEMA), wiring);

        var result = GraphQL.newGraphQL(schema).build().execute(
                ExecutionInput.newExecutionInput()
                        .query("{ parents { id child { id } } }")
                        .graphQLContext(Map.of("graphitronContext", graphitronContext))
                        .dataLoaderRegistry(new DataLoaderRegistry())
                        .build()
        );

        assertThat(result.getErrors()).isEmpty();
        List<Map<String, Object>> parents = (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("parents");
        assertThat(parents)
                .extracting(it -> ((Map<String, Object>) it.get("child")).get("id"))
                .isEqualTo(parents.stream().map(it -> it.get("id")).toList());
        return batchSizes;
    }
}
