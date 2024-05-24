package no.fellesstudentsystem.graphql.helpers.resolvers;

import graphql.ErrorType;
import graphql.GraphqlErrorBuilder;
import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import graphql.relay.Edge;
import graphql.schema.DataFetchingEnvironment;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.functions.DBCount;
import no.fellesstudentsystem.graphql.helpers.functions.DBQuery;
import no.fellesstudentsystem.graphql.helpers.functions.DBQueryRoot;
import no.fellesstudentsystem.graphql.helpers.functions.TransformCall;
import no.fellesstudentsystem.graphql.helpers.selection.ConnectionSelectionSet;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.fellesstudentsystem.graphql.relay.ConnectionImpl;
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;
import org.jetbrains.annotations.NotNull;
import org.jooq.DSLContext;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DataFetcher {
    private final AbstractTransformer abstractTransformer;
    private final DataFetchingEnvironment env;
    private final DSLContext ctx;

    /**
     * Create a dataloader for a resolver.
     * @param env Environment for the resolver.
     * @param ctx Context from the resolver.
     */
    public DataFetcher(DataFetchingEnvironment env, DSLContext ctx) {
        this.env = env;
        this.ctx = ResolverHelpers.selectContext(env, ctx);
        this.abstractTransformer = null;
    }

    /**
     * Create a dataloader for a resolver.
     * @param abstractTransformer A transformer that can transform jOOQ records to Java-records and back.
     */
    public DataFetcher(AbstractTransformer abstractTransformer) {
        this.env = abstractTransformer.getEnv();
        this.ctx = ResolverHelpers.selectContext(env, abstractTransformer.getCtx());
        this.abstractTransformer = abstractTransformer;
    }

    public AbstractTransformer getAbstractTransformer() {
        return abstractTransformer;
    }

    public DataFetchingEnvironment getEnv() {
        return env;
    }

    public DSLContext getCtx() {
        return ctx;
    }

    /**
     * Load the data for a root resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @return A resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T> CompletableFuture<T> load(DBQueryRoot<T> dbFunction) {
        return CompletableFuture.completedFuture(dbFunction.callDBMethod(ResolverHelpers.selectContext(env, ctx), ResolverHelpers.getSelectionSet(env)));
    }

    /**
     * Load the data for a root resolver. The result is paginated.
     * @param pageSize Size of the pages for pagination.
     * @param maxNodes Limit on how many elements may be fetched at once.
     * @param dbFunction Function to call to retrieve the query data.
     * @param countFunction Function to call to retrieve the total count of elements that could be potentially retrieved.
     * @param idFunction Function that extracts an ID from the fetched type.
     * @return A paginated resolver result.
     * @param <T> Type that the resolver fetches.
     */
    @NotNull
    public <T> CompletableFuture<ExtendedConnection<T>> load(int pageSize, int maxNodes, DBQueryRoot<List<T>> dbFunction, DBCount<String> countFunction, Function<T, String> idFunction) {
        var selectionSet = new ConnectionSelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = dbFunction.callDBMethod(ctx, selectionSet);
        var totalCount = countFunction.callDBMethod(ctx, Set.of(), selectionSet);
        return getPaginatedConnection(dbResult, pageSize, totalCount, maxNodes, idFunction);
    }

    /**
     * Load the data for a resolver.
     * @param resolveName Name of the resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @param id ID of the queried element.
     * @return A paginated resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T> CompletableFuture<T> load(String resolveName, String id, DBQuery<String, T> dbFunction) {
        return getDataLoader(resolveName, dbFunction)
                .load(env.getExecutionStepInfo().getPath().toString() + "||" + id, env);
    }


    /**
     * Load the data for a resolver. The result is paginated.
     * @param pageSize Size of the pages for pagination.
     * @param maxNodes Limit on how many elements may be fetched at once.
     * @param dbFunction Function to call to retrieve the query data.
     * @param countFunction Function to call to retrieve the total count of elements that could be potentially retrieved.
     * @param idFunction Function that extracts an ID from the fetched type.
     * @return A paginated resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T> CompletableFuture<ExtendedConnection<T>> load(String resolveName, String id, int pageSize, int maxNodes, DBQuery<String, List<T>> dbFunction, DBCount<String> countFunction, Function<T, String> idFunction) {
        return getDataLoader(resolveName, pageSize, maxNodes, dbFunction, countFunction, idFunction)
                .load(env.getExecutionStepInfo().getPath().toString() + "||" + id, env);
    }

    /**
     * Load the data for a resolver. The result is paginated.
     * @param resolveName Name of the resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @param id ID of the queried element.
     * @return A paginated resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T> CompletableFuture<List<T>> loadNonNullable(String resolveName, String id, DBQuery<String, List<T>> dbFunction) {
        return getDataLoaderNonNullable(resolveName, dbFunction)
                .load(env.getExecutionStepInfo().getPath().toString() + "||" + id, env)
                .thenApply(data -> Optional.ofNullable(data).orElse(List.of()));
    }

    /**
     * Load the data for a resolver. The result is paginated.
     * @param resolveName Name of the resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @param dbTransform Function to call to transform the output to schema types.
     * @param id ID of the queried element.
     * @return A paginated resolver result.
     * @param <T> Type that the resolver fetches.
     */
    public <T, U> CompletableFuture<List<U>> loadNonNullable(String resolveName, String id, DBQuery<String, T> dbFunction, TransformCall<T, U> dbTransform) {
        return getDataLoaderNonNullable(resolveName, dbFunction, dbTransform)
                .load(env.getExecutionStepInfo().getPath().toString() + "||" + id, env)
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
    @NotNull
    public <K, V> CompletableFuture<List<V>> loadLookup(List<List<K>> keys, DBQuery<String, V> dbFunction) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        var mergedKeys = mergeKeys(keys, env);

        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = dbFunction.callDBMethod(ctx, new HashSet<>(mergedKeys), selectionSet);
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

    /**
     * Create a paginated dataloader for a resolver. Typically used for pagination on non-root queries.
     * @param resolveName Name of the resolver.
     * @param pageSize Size of the pages for pagination.
     * @param maxNodes Limit on how many elements may be fetched at once.
     * @param dbFunction Function to call to retrieve the query data.
     * @param countFunction Function to call to retrieve the total count of elements that could be potentially retrieved.
     * @param idFunction Function that extracts an ID from the fetched type.
     * @return A paginated dataloader.
     * @param <T> Type that the resolver fetches.
     */
    private <T> DataLoader<String, ExtendedConnection<T>> getDataLoader(String resolveName, int pageSize, int maxNodes, DBQuery<String, List<T>> dbFunction, DBCount<String> countFunction, Function<T, String> idFunction) {
        return env.getDataLoaderRegistry().computeIfAbsent(resolveName, name ->
                DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<String, ExtendedConnection<T>>) (keys, batchEnvLoader) ->
                        getMappedDataLoader(keys, new ConnectionSelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader)), maxNodes, pageSize, dbFunction, countFunction, idFunction)
                )
        );
    }

    /**
     * Create a dataloader for a resolver.
     * @param resolveName Name of the resolver.
     * @param dbFunction Function to call to retrieve the query data.
     * @return A basic dataloader without pagination.
     * @param <T> Type that the resolver fetches.
     */
    @NotNull
    private <T> DataLoader<String, T> getDataLoader(String resolveName, DBQuery<String, T> dbFunction) {
        return env.getDataLoaderRegistry().computeIfAbsent(resolveName, name ->
                DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<String, T>) (keys, batchEnvLoader) ->
                        getMappedDataLoader(keys, new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader)), dbFunction)
                )
        );
    }

    @NotNull
    private <T> DataLoader<String, T> getDataLoaderNonNullable(String resolveName, DBQuery<String, T> dbFunction) {
        return env.getDataLoaderRegistry().computeIfAbsent(resolveName, name ->
                DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<String, T>) (keys, batchEnvLoader) ->
                        getMappedDataLoader(keys, new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader)), dbFunction)
                )
        );
    }

    @NotNull
    private <T, U> DataLoader<String, List<U>> getDataLoaderNonNullable(String resolveName, DBQuery<String, T> dbFunction, TransformCall<T, U> dbTransform) {
        return env.getDataLoaderRegistry().computeIfAbsent(resolveName, name ->
                DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<String, U>) (keys, batchEnvLoader) ->
                        getMappedDataLoader(keys, new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader)), dbFunction, dbTransform)
                )
        );
    }

    @NotNull
    private <T> CompletableFuture<Map<String, ExtendedConnection<T>>> getMappedDataLoader(
            Set<String> keys,
            SelectionSet selectionSet,
            int maxNodes,
            int pageSize,
            DBQuery<String, List<T>> dbFunction,
            DBCount<String> countFunction, Function<T, String> idFunction
    ) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        var keyToId = getKeyToId(keys);
        var idSet = new HashSet<>(keyToId.values());
        return getPaginatedConnection(
                resultAsMap(keyToId, dbFunction.callDBMethod(ctx, idSet, selectionSet)),
                pageSize,
                countFunction.callDBMethod(ctx, idSet, selectionSet),
                maxNodes,
                idFunction
        );
    }

    @NotNull
    private <T> CompletableFuture<Map<String, T>> getMappedDataLoader(Set<String> keys, SelectionSet selectionSet, DBQuery<String, T> dbFunction) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        var keyToId = getKeyToId(keys);
        return CompletableFuture.completedFuture(resultAsMap(keyToId, dbFunction.callDBMethod(ctx, new HashSet<>(keyToId.values()), selectionSet)));
    }

    @NotNull
    private <T, U> CompletableFuture<Map<String, U>> getMappedDataLoader(Set<String> keys, SelectionSet selectionSet, DBQuery<String, T> dbFunction, TransformCall<T, U> dbTransform) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        var keyToId = getKeyToId(keys);
        return CompletableFuture.completedFuture(
                resultAsMap(keyToId, dbFunction.callDBMethod(ctx, new HashSet<>(keyToId.values()), selectionSet))
                        .entrySet()
                        .stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, it -> dbTransform.transform(abstractTransformer, it.getValue())))
        );
    }

    @NotNull
    private <T> Map<String, T> resultAsMap(Map<String, String> keyToId, Map<String, T> dbResult) {
        return keyToId
                .entrySet()
                .stream()
                .filter(it -> it.getValue() != null)
                .filter(it -> dbResult.get(it.getValue()) != null)
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                it -> {
                                    var dbValue = dbResult.get(it.getValue());
                                    return dbValue instanceof List<?> ? (T)((List<?>)dbValue).stream().filter(Objects::nonNull).collect(Collectors.toList()) : dbValue;
                                }
                        )
                );
    }

    @NotNull
    private Map<String, String> getKeyToId(Set<String> keys) {
        return keys.stream().collect(Collectors.toMap(s -> s, s -> s.substring(s.lastIndexOf("||") + 2)));
    }

    @NotNull
    private <T> CompletableFuture<ExtendedConnection<T>> getPaginatedConnection(List<T> dbResult, int pageSize, Integer totalCount, int maxNodes, Function<T, String> idFunction) {
        return CompletableFuture.completedFuture(createPagedResult(dbResult, pageSize, totalCount != null ? Math.min(maxNodes, totalCount) : null, idFunction));
    }

    @NotNull
    private <T> CompletableFuture<Map<String, ExtendedConnection<T>>> getPaginatedConnection(Map<String, List<T>> dbResult, int pageSize, Integer totalCount, int maxNodes, Function<T, String> idFunction) {
        var pagedResult = dbResult.entrySet().stream().map(resultEntry -> {
            var pagedResultEntry = createPagedResult(resultEntry.getValue(), pageSize, totalCount != null ? Math.min(maxNodes, totalCount) : null, idFunction);
            return new AbstractMap.SimpleEntry<String, ExtendedConnection<T>>(resultEntry.getKey(), pagedResultEntry);
        }).collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));
        return CompletableFuture.completedFuture(pagedResult);
    }

    private <T> ConnectionImpl<T> createPagedResult(List<T> dbResult, int pageSize, Integer totalCount, Function<T, String> idFunction) {
        var items = dbResult.subList(0, Math.min(dbResult.size(), pageSize));
        DefaultPageInfo pageInfo;
        if (items.isEmpty()) {
            pageInfo = new DefaultPageInfo(null, null, false, dbResult.size() > pageSize);
        } else {
            var itemStart = items.get(0);
            var itemEnd = items.get(items.size() - 1);
            pageInfo = new DefaultPageInfo(
                    new DefaultConnectionCursor(itemStart != null ? idFunction.apply(itemStart) : null),
                    new DefaultConnectionCursor(itemEnd != null ? idFunction.apply(itemEnd) : null),
                    false,
                    dbResult.size() > pageSize
            );
        }

        List<Edge<T>> edges = items
                .stream()
                .map(item -> new DefaultEdge<>(item, new DefaultConnectionCursor(item != null ? idFunction.apply(item) : null)))
                .collect(Collectors.toList());

        return ConnectionImpl
                .<T>builder()
                .setPageInfo(pageInfo)
                .setNodes(items)
                .setEdges(edges)
                .setTotalCount(totalCount)
                .build();
    }
}
