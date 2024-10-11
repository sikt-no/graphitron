package no.fellesstudentsystem.graphql.helpers.resolvers;

import graphql.schema.DataFetchingEnvironment;
import no.fellesstudentsystem.graphql.helpers.functions.TransformCall;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.fellesstudentsystem.graphql.relay.ConnectionImpl;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.CONNECTION_TOTAL_COUNT;

public class ServiceDataFetcher<A extends AbstractTransformer> extends AbstractFetcher {
    private final A abstractTransformer;

    /**
     * Create a dataloader for a resolver.
     * @param env Environment for the resolver.
     * @param ctx Context from the resolver.
     */
    public ServiceDataFetcher(DataFetchingEnvironment env) {
        this(env, null);
    }

    /**
     * Create a dataloader for a resolver.
     * @param abstractTransformer A transformer that can transform jOOQ records to Java-records and back.
     */
    public ServiceDataFetcher(A abstractTransformer) {
        this(abstractTransformer.getEnv(), abstractTransformer);
    }

    /**
     * Create a dataloader for a resolver.
     * @param env Environment for the resolver.
     * @param ctx Context from the resolver.
     * @param abstractTransformer A transformer that can transform jOOQ records to Java-records and back.
     */
    private ServiceDataFetcher(DataFetchingEnvironment env, A abstractTransformer) {
        super(env);
        this.abstractTransformer = abstractTransformer;
    }

    public A getAbstractTransformer() {
        return abstractTransformer;
    }

    /**
     * Load the data for a root resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @return A resolver result.
     * @param <T> Type that the query returns.
     * @param <U> Type that the resolver fetches.
     */
    public <T, U> CompletableFuture<U> load(Supplier<T> dbFunction, TransformCall<A, T, U> dbTransform) {
        return CompletableFuture.completedFuture(dbTransform.transform(abstractTransformer, dbFunction.get()));
    }

    /**
     * Load the data for a root resolver. The result is paginated.
     * @param pageSize Size of the pages for pagination.
     * @param maxNodes Limit on how many elements may be fetched at once.
     * @param dbFunction Function to call to retrieve the query data.
     * @param countFunction Function to call to retrieve the total count of elements that could be potentially retrieved.
     * @param dbTransform Function that maps the query output to the resolver output.
     * @param connectionFunction Function that converts the result of the query to a GraphQL connection structure.
     * @return A paginated resolver result.
     * @param <T> Type that the query returns.
     * @param <U> Type that the resolver fetches.
     */
    public <T, U, V> CompletableFuture<V> loadPaginated(
            int pageSize,
            int maxNodes,
            Supplier<List<Pair<String, T>>> dbFunction,
            Function<Set<String>, Integer> countFunction,
            TransformCall<A, List<Pair<String, T>>, List<Pair<String, U>>> dbTransform,
            Function<ConnectionImpl<U>, V> connectionFunction
    ) {
        return CompletableFuture.completedFuture(
                getPaginatedConnection(
                        dbTransform.transform(abstractTransformer, dbFunction.get()),
                        pageSize,
                        connectionSelection.contains(CONNECTION_TOTAL_COUNT.getName()) ? countFunction.apply(Set.of()) : null,
                        maxNodes,
                        connectionFunction
                )
        );
    }

    /**
     * Load the data for a resolver.
     * @param resolveName Name of the resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @param dbTransform Function that maps the query output to the resolver output.
     * @param id ID of the queried element.
     * @return A resolver result.
     * @param <T> Type that the query returns.
     * @param <U> Type that the resolver fetches.
     */
    public <T, U> CompletableFuture<U> load(String resolveName, String id, Function<Set<String>, Map<String, T>> dbFunction, TransformCall<A, T, U> dbTransform) {
        return getLoader(resolveName, (keys, set) -> getMappedDataLoader(keys, dbFunction, dbTransform))
                .load(asKeyPath(id), env);
    }

    /**
     * Load the data for a resolver. The result is paginated.
     * @param pageSize Size of the pages for pagination.
     * @param maxNodes Limit on how many elements may be fetched at once.
     * @param dbFunction Function to call to retrieve the query data.
     * @param countFunction Function to call to retrieve the total count of elements that could be potentially retrieved.
     * @param dbTransform Function that maps the query output to the resolver output.
     * @param connectionFunction Function that converts the result of the query to a GraphQL connection structure.
     * @return A paginated resolver result.
     * @param <T> Type that the query returns.
     * @param <U> Type that the resolver fetches.
     */
    public <T, U, V> CompletableFuture<V> loadPaginated(
            String resolveName,
            String id,
            int pageSize,
            int maxNodes,
            Function<Set<String>, Map<String, List<Pair<String, T>>>> dbFunction,
            Function<Set<String>, Integer> countFunction,
            TransformCall<A, List<Pair<String, T>>, List<Pair<String, U>>> dbTransform,
            Function<ConnectionImpl<U>, V> connectionFunction
    ) {
        return getConnectionLoader(resolveName, (keys, set) -> getMappedDataLoader(keys, maxNodes, pageSize, dbFunction, countFunction, dbTransform, connectionFunction))
                .load(asKeyPath(id), env);
    }

    /**
     * Load the data for a resolver. The result is paginated.
     * @param resolveName Name of the resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @param dbTransform Function to call to transform the output to schema types.
     * @param id ID of the queried element.
     * @return A resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T, U> CompletableFuture<List<U>> loadNonNullable(String resolveName, String id, Function<Set<String>, Map<String, List<T>>> dbFunction, TransformCall<A, List<T>, List<U>> dbTransform) {
        return getLoader(resolveName, (keys, set) -> getMappedDataLoader(keys, dbFunction, dbTransform))
                .load(asKeyPath(id), env)
                .thenApply(data -> Optional.ofNullable(data).orElse(List.of()));
    }

    private <T, U, V> CompletableFuture<Map<String, V>> getMappedDataLoader(
            Set<String> keys,
            int maxNodes,
            int pageSize,
            Function<Set<String>, Map<String, List<Pair<String, T>>>> dbFunction,
            Function<Set<String>, Integer> countFunction,
            TransformCall<A, List<Pair<String, T>>, List<Pair<String, U>>> dbTransform,
            Function<ConnectionImpl<U>, V> connectionFunction
    ) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        var keyToId = getKeyToId(keys);
        var idSet = new HashSet<>(keyToId.values());
        return CompletableFuture.completedFuture(
                getPaginatedConnection(
                        connectionSelection.contains(CONNECTION_TOTAL_COUNT.getName())
                                ? resultAsMap(keyToId, dbFunction.apply(idSet))
                                .entrySet()
                                .stream()
                                .collect(Collectors.toMap(Map.Entry::getKey, it -> dbTransform.transform(abstractTransformer, it.getValue()))) : Map.of(),
                        pageSize,
                        connectionSelection.contains(CONNECTION_TOTAL_COUNT.getName()) ? countFunction.apply(idSet) : null,
                        maxNodes,
                        connectionFunction
                )
        );
    }

    private <T, U> CompletableFuture<Map<String, U>> getMappedDataLoader(Set<String> keys, Function<Set<String>, Map<String, T>> dbFunction, TransformCall<A, T, U> dbTransform) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        var keyToId = getKeyToId(keys);
        return CompletableFuture.completedFuture(
                resultAsMap(keyToId, dbFunction.apply(new HashSet<>(keyToId.values())))
                        .entrySet()
                        .stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, it -> dbTransform.transform(abstractTransformer, it.getValue())))
        );
    }
}
