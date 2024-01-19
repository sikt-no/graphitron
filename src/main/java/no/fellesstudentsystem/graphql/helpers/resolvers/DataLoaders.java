package no.fellesstudentsystem.graphql.helpers.resolvers;

import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import graphql.relay.Edge;
import graphql.schema.DataFetchingEnvironment;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.functions.DBCount;
import no.fellesstudentsystem.graphql.helpers.functions.DBQuery;
import no.fellesstudentsystem.graphql.helpers.functions.DBQueryIterable;
import no.fellesstudentsystem.graphql.helpers.functions.DBQueryRoot;
import no.fellesstudentsystem.graphql.helpers.selection.ConnectionSelectionSet;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.relay.ConnectionImpl;
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DataLoaders {
    public static <T> DataLoader<String, ExtendedConnection<T>> getDataLoader(DataFetchingEnvironment env, String resolveName, int pageSize, int maxNodes, DBQueryIterable<String, T> dbFunction, DBCount<String> countFunction, Function<T, String> idFunction) {
        return env.getDataLoaderRegistry().computeIfAbsent(resolveName, name ->
                DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<String, ExtendedConnection<T>>) (keys, batchEnvLoader) ->
                        getMappedDataLoader(keys, new ConnectionSelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader)), maxNodes, pageSize, dbFunction, countFunction, idFunction)
                )
        );
    }

    @NotNull
    public static <T> DataLoader<String, T> getDataLoader(DataFetchingEnvironment env, String resolveName, DBQuery<String, T> dbFunction) {
        return env.getDataLoaderRegistry().computeIfAbsent(resolveName, name ->
                DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<String, T>) (keys, batchEnvLoader) ->
                        getMappedDataLoader(keys, new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader)), dbFunction)
                )
        );
    }

    @NotNull
    public static <T> CompletableFuture<ExtendedConnection<T>> loadData(DataFetchingEnvironment env, int pageSize, int maxNodes, DBQueryRoot<T> dbFunction, DBCount<String> countFunction, Function<T, String> idFunction) {
        var selectionSet = new ConnectionSelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = dbFunction.callDBMethod(selectionSet);
        var totalCount = countFunction.callDBMethod(Set.of(), selectionSet);
        return getPaginatedConnection(dbResult, pageSize, totalCount, maxNodes, idFunction);
    }

    @NotNull
    public static <K, V> CompletableFuture<List<V>> loadDataAsLookup(DataFetchingEnvironment env, List<K> keys, DBQuery<K, V> dbFunction) {
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = dbFunction.callDBMethod(new HashSet<>(keys), selectionSet);
        var orderedResult = keys.stream().map(dbResult::get).collect(Collectors.toList());
        return CompletableFuture.completedFuture(orderedResult);
    }

    public static <T> CompletableFuture<T> load(DataLoader<String, T> loader, String id, DataFetchingEnvironment env) {
        return loader.load(env.getExecutionStepInfo().getPath().toString() + "||" + id, env);
    }

    public static <T> CompletableFuture<List<T>> loadNonNullable(DataLoader<String, List<T>> loader, String id, DataFetchingEnvironment env) {
        return loader.load(env.getExecutionStepInfo().getPath().toString() + "||" + id, env).thenApply(data -> Optional.ofNullable(data).orElse(List.of()));
    }

    public static <T, U extends T> CompletableFuture<T> loadInterfaceData(DataFetchingEnvironment env, String table, String keyToLoad, DBQuery<String, U> dbFunction) {
        return env
                .getDataLoaderRegistry()
                .<String, T>computeIfAbsent(table, name ->
                        DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<String, U>) (ids, loaderEnvironment) ->
                                CompletableFuture.completedFuture(dbFunction.callDBMethod(ids, new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(loaderEnvironment))))
                        )
                )
                .load(keyToLoad, env);
    }

    @NotNull
    private static <T> CompletableFuture<Map<String, ExtendedConnection<T>>> getMappedDataLoader(Set<String> keys, SelectionSet selectionSet, int maxNodes, int pageSize, DBQueryIterable<String, T> dbFunction, DBCount<String> countFunction, Function<T, String> idFunction) {
        var keyToId = getKeyToId(keys);
        var idSet = new HashSet<>(keyToId.values());
        var mapResult = resultAsMap(keyToId, dbFunction.callDBMethod(idSet, selectionSet));
        var totalCount = countFunction.callDBMethod(idSet, selectionSet);
        return DataLoaders.getPaginatedConnection(mapResult, pageSize, totalCount, maxNodes, idFunction);
    }

    @NotNull
    private static <T> CompletableFuture<Map<String, T>> getMappedDataLoader(Set<String> keys, SelectionSet selectionSet, DBQuery<String, T> dbFunction) {
        var keyToId = getKeyToId(keys);
        return CompletableFuture.completedFuture(resultAsMap(keyToId, dbFunction.callDBMethod(new HashSet<>(keyToId.values()), selectionSet)));
    }

    @NotNull
    private static <T> Map<String, T> resultAsMap(Map<String, String> keyToId, Map<String, T> dbResult) {
        return keyToId
                .entrySet()
                .stream()
                .filter(it -> dbResult.get(it.getValue()) != null)
                .collect(Collectors.toMap(Map.Entry::getKey, it -> dbResult.get(it.getValue())));
    }

    @NotNull
    private static Map<String, String> getKeyToId(Set<String> keys) {
        return keys.stream().collect(Collectors.toMap(s -> s, s -> s.substring(s.lastIndexOf("||") + 2)));
    }

    @NotNull
    public static <T> CompletableFuture<ExtendedConnection<T>> getPaginatedConnection(List<T> dbResult, int pageSize, Integer totalCount, int maxNodes, Function<T, String> idFunction) {
        return CompletableFuture.completedFuture(createPagedResult(dbResult, pageSize, totalCount != null ? Math.min(maxNodes, totalCount) : null, idFunction));
    }

    @NotNull
    public static <T> CompletableFuture<Map<String, ExtendedConnection<T>>> getPaginatedConnection(Map<String, List<T>> dbResult, int pageSize, Integer totalCount, int maxNodes, Function<T, String> idFunction) {
        var pagedResult = dbResult.entrySet().stream().map(resultEntry -> {
            var pagedResultEntry = createPagedResult(resultEntry.getValue(), pageSize, totalCount != null ? Math.min(maxNodes, totalCount) : null, idFunction);
            return new AbstractMap.SimpleEntry<String, ExtendedConnection<T>>(resultEntry.getKey(), pagedResultEntry);
        }).collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));
        return CompletableFuture.completedFuture(pagedResult);
    }

    private static <T> ConnectionImpl<T> createPagedResult(List<T> dbResult, int pageSize, Integer totalCount, Function<T, String> idFunction) {
        var items = dbResult.subList(0, Math.min(dbResult.size(), pageSize));
        DefaultPageInfo pageInfo;
        if (items.isEmpty()) {
            pageInfo = new DefaultPageInfo(null, null, false, dbResult.size() > pageSize);
        } else {
            pageInfo = new DefaultPageInfo(
                    new DefaultConnectionCursor(idFunction.apply(items.get(0))),
                    new DefaultConnectionCursor(idFunction.apply(items.get(items.size() - 1))),
                    false,
                    dbResult.size() > pageSize
            );
        }

        List<Edge<T>> edges = items
                .stream()
                .map(item -> new DefaultEdge<>(item, new DefaultConnectionCursor(idFunction.apply(item))))
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
