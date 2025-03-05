package no.sikt.graphql.helpers.resolvers;

import graphql.ErrorType;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphql.exception.ValidationViolationGraphQLException;
import no.sikt.graphql.helpers.EnvironmentUtils;
import no.sikt.graphql.helpers.functions.DBCount;
import no.sikt.graphql.helpers.functions.DBQuery;
import no.sikt.graphql.helpers.functions.DBQueryRoot;
import no.sikt.graphql.helpers.selection.SelectionSet;
import no.sikt.graphql.relay.ConnectionImpl;
import org.apache.commons.lang3.tuple.Pair;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_TOTAL_COUNT;

public class DataFetcherHelper extends AbstractFetcher {
    /**
     * Create a dataloader for a resolver.
     * @param env Environment for the resolver.
     */
    public DataFetcherHelper(DataFetchingEnvironment env) {
        super(env);
    }

    /**
     * Load the data for a root resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @return A resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T> CompletableFuture<T> load(DBQueryRoot<T> dbFunction) {
        return CompletableFuture.completedFuture(dbFunction.callDBMethod(this.ctx, selection));
    }

    /**
     * Load the data for a root resolver. The result is paginated.
     * @param pageSize Size of the pages for pagination.
     * @param maxNodes Limit on how many elements may be fetched at once.
     * @param dbFunction Function to call to retrieve the query data.
     * @param countFunction Function to call to retrieve the total count of elements that could be potentially retrieved.
     * @param connectionFunction Function that converts the result of the query to a GraphQL connection structure.
     * @return A paginated resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T, U> CompletableFuture<U> loadPaginated(
            int pageSize,
            int maxNodes,
            DBQueryRoot<List<Pair<String, T>>> dbFunction,
            DBCount<String> countFunction,
            Function<ConnectionImpl<T>, U> connectionFunction
    ) {
        return CompletableFuture.completedFuture(
                getPaginatedConnection(
                        dbFunction.callDBMethod(ctx, connectionSelection),
                        pageSize,
                        connectionSelection.contains(CONNECTION_TOTAL_COUNT.getName()) ? countFunction.callDBMethod(ctx, Set.of()) : -1,
                        maxNodes,
                        connectionFunction
                )
        );
    }

    /**
     * Load the data for a resolver.
     * @param loaderName Name of the resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @param key Key for the queried element.
     * @return A resolver result.
     * @param <V> Type that the resolver fetches.
     */
    public <K, V> CompletableFuture<V> load(String loaderName, K key, DBQuery<String, V> dbFunction) {
        return getLoader(loaderName, (keys, set) -> getMappedDataLoader(keys, set, dbFunction))
                .load(asKeyPath(key), env);
    }

    /**
     * Load the data for a resolver. The result is paginated.
     * @param pageSize Size of the pages for pagination.
     * @param maxNodes Limit on how many elements may be fetched at once.
     * @param dbFunction Function to call to retrieve the query data.
     * @param countFunction Function to call to retrieve the total count of elements that could be potentially retrieved.
     * @param connectionFunction Function that converts the result of the query to a GraphQL connection structure.
     * @return A paginated resolver result.
     * @param <C> Connection type that the resolver fetches.
     */
    public <K, V, C> CompletableFuture<C> loadPaginated(
            String resolveName,
            K key,
            int pageSize,
            int maxNodes,
            DBQuery<String, List<Pair<String, V>>> dbFunction,
            DBCount<String> countFunction,
            Function<ConnectionImpl<V>, C> connectionFunction
    ) {
        return getConnectionLoader(resolveName, (keys, set) -> getMappedDataLoader(keys, set, maxNodes, pageSize, dbFunction, countFunction, connectionFunction))
                .load(asKeyPath(key), env);
    }

    /**
     * Load the data for a resolver.
     * @param resolveName Name of the resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @param key ID of the queried element.
     * @return A resolver result.
     * @param <V> Type that the resolver fetches.
     */
    public <K, V> CompletableFuture<List<V>> loadNonNullable(String resolveName, K key, DBQuery<String, List<V>> dbFunction) {
        return getLoader(resolveName, (keys, set) -> getMappedDataLoader(keys, set, dbFunction))
                .load(asKeyPath(key), env)
                .thenApply(data -> Optional.ofNullable(data).orElse(List.of()));
    }

    /**
     * Load the data for a resolver in a lookup format.
     * @param keys The lookup keys that are necessary for match rows in the result with the original resolver input.
     * @param dbFunction Function to call to retrieve the query data.
     * @return A resolver result without pagination.
     * @param <K> The key type that this lookup uses.
     * @param <V> Type that the resolver fetches.
     */
    public <K, V> CompletableFuture<List<V>> loadLookup(List<List<K>> keys, DBQuery<String, V> dbFunction) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        var mergedKeys = mergeKeys(keys, env);

        var dbResult = dbFunction.callDBMethod(ctx, new HashSet<>(mergedKeys), selection);
        var orderedResult = mergedKeys.stream().map(dbResult::get).collect(Collectors.toList());
        return CompletableFuture.completedFuture(orderedResult);
    }

    public static <K> List<String> mergeKeys(List<List<K>> keys, DataFetchingEnvironment env) {
        if (keys.isEmpty()) {
            return List.of();
        }

        // This assumes all keys are correlated.
        var nKeys = keys.get(0).size();
        if (keys.stream().map(List::size).anyMatch(it -> it != nKeys)) {
            throw new ValidationViolationGraphQLException(
                    List.of(
                            GraphqlErrorBuilder
                                    .newError(env)
                                    .path(env.getExecutionStepInfo().getPath())
                                    .message("Keys sets have differing lengths. For this type of query, each key field is required to be an array of equal length.")
                                    .errorType(ErrorType.ValidationError)
                                    .build()
                    )
            );
        }

        var mergedKeys = new ArrayList<>(Collections.nCopies(nKeys, ""));
        for (var keyList : keys) {
            for (int i = 0; i < nKeys; i++) {
                var currentKey = mergedKeys.get(i);
                mergedKeys.set(i, (currentKey.isEmpty() ? "" : currentKey + ",") + keyList.get(i));
            }
        }

        return mergedKeys;
    }

    /**
     * Load the data for an interface resolver.
     * @param loaderName The source from which the data should be loaded.
     * @param keyToLoad The specific key that should be loaded.
     * @param dbFunction Function to call to retrieve the query data.
     * @return A resolver result without pagination.
     * @param <V1> Type that the resolver fetches.
     */
    public <K, V1, V0 extends V1> CompletableFuture<V1> loadInterface(String loaderName, K keyToLoad, DBQuery<K, V0> dbFunction) {
        return env
                .getDataLoaderRegistry()
                .<K, V1>computeIfAbsent(loaderName, name ->
                        DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<K, V0>) (keys, loaderEnvironment) ->
                                CompletableFuture.completedFuture(dbFunction.callDBMethod(ctx, keys, new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(loaderEnvironment))))
                        )
                )
                .load(keyToLoad, env);
    }

    private <K, V, C> CompletableFuture<Map<KeyWithPath<K>, C>> getMappedDataLoader(
            Set<KeyWithPath<K>> keys,
            SelectionSet selectionSet,
            int maxNodes,
            int pageSize,
            DBQuery<String, List<Pair<String, V>>> dbFunction,
            DBCount<String> countFunction,
            Function<ConnectionImpl<V>, C> connectionFunction
    ) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        var keyToId = getKeyToId(keys);
        var idSet = new HashSet<>(keyToId.values());
        return CompletableFuture.completedFuture(
                getPaginatedConnection(
                        resultAsMap(keyToId, dbFunction.callDBMethod(ctx, idSet, selectionSet)),
                        pageSize,
                        connectionSelection.contains(CONNECTION_TOTAL_COUNT.getName()) ? countFunction.callDBMethod(ctx, idSet) : -1,
                        maxNodes,
                        connectionFunction
                )
        );
    }


    private <K, V> CompletableFuture<Map<KeyWithPath<K>, V>> getMappedDataLoader(Set<KeyWithPath<K>> keys, SelectionSet selectionSet, DBQuery<String, V> dbFunction) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        var keyToId = getKeyToId(keys);
        return CompletableFuture.completedFuture(resultAsMap(keyToId, dbFunction.callDBMethod(ctx, new HashSet<>(keyToId.values()), selectionSet)));
    }
}
