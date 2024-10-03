package no.fellesstudentsystem.graphql.helpers.resolvers;

import graphql.ErrorType;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.functions.DBCount;
import no.fellesstudentsystem.graphql.helpers.functions.DBQuery;
import no.fellesstudentsystem.graphql.helpers.functions.DBQueryRoot;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.relay.ConnectionImpl;
import org.apache.commons.lang3.tuple.Pair;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;
import org.jooq.DSLContext;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.CONNECTION_TOTAL_COUNT;

public class DataFetcher extends AbstractFetcher {
    /**
     * Create a dataloader for a resolver.
     * @param env Environment for the resolver.
     * @param ctx Context from the resolver.
     */
    public DataFetcher(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
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
     * @param resolveName Name of the resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @param id ID of the queried element.
     * @return A resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T> CompletableFuture<T> load(String resolveName, String id, DBQuery<String, T> dbFunction) {
        return getLoader(resolveName, (keys, set) -> getMappedDataLoader(keys, set, dbFunction))
                .load(asKeyPath(id), env);
    }

    /**
     * Load the data for a resolver. The result is paginated.
     * @param pageSize Size of the pages for pagination.
     * @param maxNodes Limit on how many elements may be fetched at once.
     * @param dbFunction Function to call to retrieve the query data.
     * @param countFunction Function to call to retrieve the total count of elements that could be potentially retrieved.
     * @param connectionFunction Function that converts the result of the query to a GraphQL connection structure.
     * @return A paginated resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T, U> CompletableFuture<U> loadPaginated(
            String resolveName,
            String id,
            int pageSize,
            int maxNodes,
            DBQuery<String, List<Pair<String, T>>> dbFunction,
            DBCount<String> countFunction,
            Function<ConnectionImpl<T>, U> connectionFunction
    ) {
        return getConnectionLoader(resolveName, (keys, set) -> getMappedDataLoader(keys, set, maxNodes, pageSize, dbFunction, countFunction, connectionFunction))
                .load(asKeyPath(id), env);
    }

    /**
     * Load the data for a resolver.
     * @param resolveName Name of the resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @param id ID of the queried element.
     * @return A resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T> CompletableFuture<List<T>> loadNonNullable(String resolveName, String id, DBQuery<String, List<T>> dbFunction) {
        return getLoader(resolveName, (keys, set) -> getMappedDataLoader(keys, set, dbFunction))
                .load(asKeyPath(id), env)
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
     * @param table The source from which the data should be loaded.
     * @param keyToLoad The specific key that should be loaded.
     * @param dbFunction Function to call to retrieve the query data.
     * @return A resolver result without pagination.
     * @param <U> Type that the resolver fetches.
     */
    public <T, U extends T> CompletableFuture<T> loadInterface(String table, String keyToLoad, DBQuery<String, U> dbFunction) {
        return env
                .getDataLoaderRegistry()
                .<String, T>computeIfAbsent(table, name ->
                        DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<String, U>) (ids, loaderEnvironment) ->
                                CompletableFuture.completedFuture(dbFunction.callDBMethod(ctx, ids, new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(loaderEnvironment))))
                        )
                )
                .load(keyToLoad, env);
    }

    private <T, U> CompletableFuture<Map<String, U>> getMappedDataLoader(
            Set<String> keys,
            SelectionSet selectionSet,
            int maxNodes,
            int pageSize,
            DBQuery<String, List<Pair<String, T>>> dbFunction,
            DBCount<String> countFunction,
            Function<ConnectionImpl<T>, U> connectionFunction
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


    private <T> CompletableFuture<Map<String, T>> getMappedDataLoader(Set<String> keys, SelectionSet selectionSet, DBQuery<String, T> dbFunction) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        var keyToId = getKeyToId(keys);
        return CompletableFuture.completedFuture(resultAsMap(keyToId, dbFunction.callDBMethod(ctx, new HashSet<>(keyToId.values()), selectionSet)));
    }
}
