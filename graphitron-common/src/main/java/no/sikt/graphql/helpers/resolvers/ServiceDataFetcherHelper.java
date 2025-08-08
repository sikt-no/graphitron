package no.sikt.graphql.helpers.resolvers;

import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphql.helpers.functions.TransformCall;
import no.sikt.graphql.helpers.selection.SelectionSet;
import no.sikt.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphql.relay.ConnectionImpl;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_TOTAL_COUNT;

public class ServiceDataFetcherHelper<A extends AbstractTransformer> extends AbstractFetcher {
    private final A abstractTransformer;

    /**
     * Create a dataloader for a resolver.
     * @param env Environment for the resolver.
     */
    public ServiceDataFetcherHelper(DataFetchingEnvironment env) {
        this(env, null);
    }

    /**
     * Create a dataloader for a resolver.
     * @param abstractTransformer A transformer that can transform jOOQ and Java-records to GraphQL and back.
     */
    public ServiceDataFetcherHelper(A abstractTransformer) {
        this(abstractTransformer.getEnv(), abstractTransformer);
    }

    /**
     * Create a dataloader for a resolver.
     * @param env Environment for the resolver.
     * @param abstractTransformer A transformer that can transform jOOQ records to Java-records and back.
     */
    private ServiceDataFetcherHelper(DataFetchingEnvironment env, A abstractTransformer) {
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
     * @param <C> Connection type that the resolver fetches.
     */
    public <T, U, C> CompletableFuture<C> loadPaginated(
            int pageSize,
            int maxNodes,
            Supplier<List<Pair<String, T>>> dbFunction,
            Function<Set<String>, Integer> countFunction,
            TransformCall<A, List<Pair<String, T>>, List<Pair<String, U>>> dbTransform,
            Function<ConnectionImpl<U>, C> connectionFunction
    ) {
        return CompletableFuture.completedFuture(
                getPaginatedConnection(
                        dbTransform.transform(abstractTransformer, dbFunction.get()),
                        pageSize,
                        connectionSelect.contains(CONNECTION_TOTAL_COUNT.getName()) ? countFunction.apply(Set.of()) : null,
                        maxNodes,
                        connectionFunction
                )
        );
    }

    /**
     * Load the data for a resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @param dbTransform Function that maps the query output to the resolver output.
     * @param key ID of the queried element.
     * @return A resolver result.
     * @param <V0> Type that the query returns.
     * @param <V1> Type that the resolver fetches.
     */
    public <K, V0, V1> CompletableFuture<V1> load(K key, Function<Set<K>, Map<K, V0>> dbFunction, TransformCall<A, V0, V1> dbTransform) {
        return getLoader((Set<KeyWithPath<K>> keys, SelectionSet set) -> getMappedDataLoader(keys, dbFunction, dbTransform))
                .load(asKeyPath(key), env);
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
     * @param <V0> Type that the query returns.
     * @param <C> Connection type that the resolver fetches.
     */
    public <K, V0, V1, C> CompletableFuture<C> loadPaginated(
            K key,
            int pageSize,
            int maxNodes,
            Function<Set<K>, Map<K, List<Pair<String, V0>>>> dbFunction,
            Function<Set<K>, Map<K,Integer>> countFunction,
            TransformCall<A, List<Pair<String, V0>>, List<Pair<String, V1>>> dbTransform,
            Function<ConnectionImpl<V1>, C> connectionFunction
    ) {
        return getConnectionLoader((Set<KeyWithPath<K>> keys, SelectionSet set) -> getMappedDataLoader(keys, maxNodes, pageSize, dbFunction, countFunction, dbTransform, connectionFunction))
                .load(asKeyPath(key), env);
    }

    /**
     * Load the data for a resolver. The result is paginated.
     * @param dbFunction Function to call to retrieve the query data.
     * @param dbTransform Function to call to transform the output to schema types.
     * @param key ID of the queried element.
     * @return A resolver result.
     * @param <V0> Type that the resolver fetches.
     */
    public <K, V0, V1> CompletableFuture<List<V1>> loadNonNullable(K key, Function<Set<K>, Map<K, List<V0>>> dbFunction, TransformCall<A, List<V0>, List<V1>> dbTransform) {
        return getLoader((Set<KeyWithPath<K>> keys, SelectionSet set) -> getMappedDataLoader(keys, dbFunction, dbTransform))
                .load(asKeyPath(key), env)
                .thenApply(data -> Optional.ofNullable(data).orElse(List.of()));
    }

    private <K, V0, V1, C> CompletableFuture<Map<KeyWithPath<K>, C>> getMappedDataLoader(
            Set<KeyWithPath<K>> keys,
            int maxNodes,
            int pageSize,
            Function<Set<K>, Map<K, List<Pair<String, V0>>>> dbFunction,
            Function<Set<K>, Map<K,Integer>> countFunction,
            TransformCall<A, List<Pair<String, V0>>, List<Pair<String, V1>>> dbTransform,
            Function<ConnectionImpl<V1>, C> connectionFunction
    ) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        var idSet = keys.stream().map(KeyWithPath::key).collect(Collectors.toSet());
        return CompletableFuture.completedFuture(
                getPaginatedConnection(
                        connectionSelect.contains(CONNECTION_TOTAL_COUNT.getName())
                                ? resultAsMap(keys, dbFunction.apply(idSet))
                                .entrySet()
                                .stream()
                                .collect(Collectors.toMap(Map.Entry::getKey, it -> dbTransform.transform(abstractTransformer, it.getValue()))) : Map.of(),
                        pageSize,
                        connectionSelect.contains(CONNECTION_TOTAL_COUNT.getName()) ? countFunction.apply(idSet) : null,
                        maxNodes,
                        connectionFunction
                )
        );
    }

    private <K, V0, V1> CompletableFuture<Map<KeyWithPath<K>, V1>> getMappedDataLoader(Set<KeyWithPath<K>> keys, Function<Set<K>, Map<K, V0>> dbFunction, TransformCall<A, V0, V1> dbTransform) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        var idSet = keys.stream().map(KeyWithPath::key).collect(Collectors.toSet());
        return CompletableFuture.completedFuture(
                resultAsMap(keys, dbFunction.apply(idSet))
                        .entrySet()
                        .stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, it -> dbTransform.transform(abstractTransformer, it.getValue())))
        );
    }
}
