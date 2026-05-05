package no.sikt.graphql.helpers.resolvers;

import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphql.helpers.functions.TransformCall;
import no.sikt.graphql.helpers.selection.SelectionSet;
import no.sikt.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphql.relay.ConnectionImpl;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.UpdatableRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     * @param dbFunction Function to call to retrieve the query data.
     * @param dbTransform Function that maps the query output to the resolver output.
     * @return A paginated resolver result.
     * @param <T> Type that the query returns.
     */
    public <T, U> CompletableFuture<ConnectionImpl<U>> loadPaginated(
            int pageSize,
            Supplier<List<Pair<String, T>>> dbFunction,
            TransformCall<A, List<Pair<String, T>>, List<Pair<String, U>>> dbTransform
    ) {
        return loadPaginated(pageSize, dbFunction, null, dbTransform);
    }

    /**
     * Load the data for a root resolver. The result is paginated.
     * @param pageSize Size of the pages for pagination.
     * @param dbFunction Function to call to retrieve the query data.
     * @param countFunction Function to call to retrieve the total count of elements that could be potentially
     *                      retrieved. This parameter will be null if the schema does not contain the optional
     *                      {@code totalCount} field.
     * @param dbTransform Function that maps the query output to the resolver output.
     * @return A paginated resolver result.
     * @param <T> Type that the query returns.
     */
    public <T, U> CompletableFuture<ConnectionImpl<U>> loadPaginated(
            int pageSize,
            Supplier<List<Pair<String, T>>> dbFunction,
            Function<Set<String>, Integer> countFunction,
            TransformCall<A, List<Pair<String, T>>, List<Pair<String, U>>> dbTransform
    ) {
        return CompletableFuture.completedFuture(
                getPaginatedConnection(
                        dbTransform.transform(abstractTransformer, dbFunction.get()),
                        pageSize,
                        countFunction != null && connectionSelect.contains(CONNECTION_TOTAL_COUNT.getName())
                        ? countFunction.apply(Set.of())
                        : null
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
     * @param dbFunction Function to call to retrieve the query data.
     * @param dbTransform Function that maps the query output to the resolver output.
     * @return A paginated resolver result.
     * @param <V0> Type that the query returns.
     */
    public <K, V0, V1> CompletableFuture<ConnectionImpl<V1>> loadPaginated(
            K key,
            int pageSize,
            Function<Set<K>, Map<K, List<Pair<String, V0>>>> dbFunction,
            TransformCall<A, List<Pair<String, V0>>, List<Pair<String, V1>>> dbTransform
    ) {
        return loadPaginated(key, pageSize, dbFunction, null, dbTransform);
    }

    /**
     * Load the data for a resolver. The result is paginated.
     * @param pageSize Size of the pages for pagination.
     * @param dbFunction Function to call to retrieve the query data.
     * @param countFunction Function to call to retrieve the total count of elements that could be potentially
     *                      retrieved. This parameter will be null if the schema does not contain the optional
     *                      {@code totalCount} field.
     * @param dbTransform Function that maps the query output to the resolver output.
     * @return A paginated resolver result.
     * @param <V0> Type that the query returns.
     */
    public <K, V0, V1> CompletableFuture<ConnectionImpl<V1>> loadPaginated(
            K key,
            int pageSize,
            Function<Set<K>, Map<K, List<Pair<String, V0>>>> dbFunction,
            Function<Set<K>, Map<K,Integer>> countFunction,
            TransformCall<A, List<Pair<String, V0>>, List<Pair<String, V1>>> dbTransform
    ) {
        return getConnectionLoader((Set<KeyWithPath<K>> keys, SelectionSet set) -> getMappedDataLoader(keys, pageSize, dbFunction, countFunction, dbTransform))
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

    private <K, V0, V1> CompletableFuture<Map<KeyWithPath<K>, ConnectionImpl<V1>>> getMappedDataLoader(
            Set<KeyWithPath<K>> keys,
            int pageSize,
            Function<Set<K>, Map<K, List<Pair<String, V0>>>> dbFunction,
            Function<Set<K>, Map<K, Integer>> countFunction,
            TransformCall<A, List<Pair<String, V0>>, List<Pair<String, V1>>> dbTransform
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
                        countFunction != null && connectionSelect.contains(CONNECTION_TOTAL_COUNT.getName())
                        ? countFunction.apply(idSet)
                        : null
                )
        );
    }

    private <K, V0, V1> CompletableFuture<Map<KeyWithPath<K>, V1>> getMappedDataLoader(Set<KeyWithPath<K>> keys, Function<Set<K>, Map<K, V0>> dbFunction, TransformCall<A, V0, V1> dbTransform) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        return CompletableFuture.completedFuture(
                resultAsMapWithPkOnlyKeys(keys, dbFunction)
                        .entrySet()
                        .stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, it -> dbTransform.transform(abstractTransformer, it.getValue())))
        );
    }

    /**
     * Like {@link #resultAsMap}, but strips non-PK columns from {@link UpdatableRecord} keys
     * returned by the service so equality with the requested PK-only lookup keys holds.
     */
    protected <K, V> Map<KeyWithPath<K>, V> resultAsMapWithPkOnlyKeys(Set<KeyWithPath<K>> keys, Function<Set<K>, Map<K, V>> dbFunction) {
        var keySet = keys.stream().map(KeyWithPath::key).collect(Collectors.toSet());
        var stripped = dbFunction.apply(keySet).entrySet().stream()
                .collect(Collectors.toMap(e -> stripNonPkColumns(e.getKey()), Map.Entry::getValue));
        return resultAsMap(keys, stripped);
    }

    /**
     * Returns an {@link UpdatableRecord} key with non-PK columns stripped; other keys are
     * returned unchanged.
     */
    @SuppressWarnings("unchecked")
    static <K> K stripNonPkColumns(K key) {
        if (key instanceof UpdatableRecord<?> record) {
            return (K) record.key().into(record.getClass());
        }
        return key;
    }
}
