package no.sikt.graphql.helpers.resolvers;

import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import graphql.relay.Edge;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphql.helpers.functions.DataLoaderMapper;
import no.sikt.graphql.helpers.selection.ConnectionSelectionSet;
import no.sikt.graphql.helpers.selection.SelectionSet;
import no.sikt.graphql.relay.ConnectionImpl;
import org.apache.commons.lang3.tuple.Pair;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class AbstractFetcher extends EnvironmentHandler {
    /**
     * Create a dataloader for a resolver.
     * @param env Environment for the resolver.
     */
    protected AbstractFetcher(DataFetchingEnvironment env) {
        super(env);
    }

    protected <K> KeyWithPath<K> asKeyPath(K key) {
        return new KeyWithPath<>(key, executionPath);
    }

    protected <K> Set<KeyWithPath<K>> asKeyPaths(Set<K> keys) {
        return keys.stream().map(this::asKeyPath).collect(Collectors.toSet());
    }

    protected <K, V> DataLoader<KeyWithPath<K>, V> getLoader(DataLoaderMapper<KeyWithPath<K>, V> mapFunction) {
        return env.getDataLoaderRegistry().computeIfAbsent(dataloaderName, name ->
                DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<KeyWithPath<K>, V>) (keys, batchEnvLoader) ->
                        mapFunction.map(keys, new SelectionSet(getSelectionSetsFromEnvironment(batchEnvLoader)))
                )
        );
    }

    protected <K, V> DataLoader<KeyWithPath<K>, V> getConnectionLoader(DataLoaderMapper<KeyWithPath<K>, V> mapFunction) {
        return env.getDataLoaderRegistry().computeIfAbsent(dataloaderName, name ->
                DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<KeyWithPath<K>, V>) (keys, batchEnvLoader) ->
                        mapFunction.map(keys, new ConnectionSelectionSet(getSelectionSetsFromEnvironment(batchEnvLoader)))
                )
        );
    }

    protected static <K, V> Map<KeyWithPath<K>, V> resultAsMap(Set<KeyWithPath<K>> keys, Map<K, V> dbResult) {
        return keys
                .stream()
                .filter(it -> it.key() != null)
                .filter(it -> dbResult.get(it.key()) != null)
                .collect(
                        Collectors.toMap(
                                Function.identity(),
                                it -> {
                                    var dbValue = dbResult.get(it.key());
                                    return dbValue instanceof List<?> ? (V)((List<?>)dbValue).stream().filter(Objects::nonNull).toList() : dbValue;
                                }
                        )
                );
    }

    protected static <T> ConnectionImpl<T> getPaginatedConnection(List<Pair<String, T>> dbResult, int pageSize, Integer totalCount, int maxNodes) {
        return createPagedResult(dbResult, pageSize, totalCount != null ? Math.min(maxNodes, totalCount) : null);
    }

    protected static <K, V> Map<KeyWithPath<K>, ConnectionImpl<V>> getPaginatedConnection(Map<KeyWithPath<K>, List<Pair<String, V>>> dbResult, int pageSize, Object countFunctionResult, int maxNodes) {
        if (countFunctionResult == null) {
            return dbResult.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> createPagedResult(entry.getValue(), pageSize, null)
            ));
        }

        if (countFunctionResult instanceof Integer singleCount) {
            int limitedCount = Math.min(maxNodes, singleCount);
            return dbResult.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> createPagedResult(entry.getValue(), pageSize, limitedCount)
            ));
        } else if (countFunctionResult instanceof Map<?, ?> countMap) {
            @SuppressWarnings("unchecked")
            Map<K, Integer> typedCountMap = (Map<K, Integer>) countMap;
            return dbResult.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> {
                        Integer count = typedCountMap.get(entry.getKey().key());
                        int limitedCount = Math.min(maxNodes, count != null ? count : 0);
                        return createPagedResult(entry.getValue(), pageSize, limitedCount);
                    }
            ));
        } else {
            throw new IllegalStateException(
                    "Unsupported count result type: " + countFunctionResult.getClass().getSimpleName() +
                            ". Expected Integer or Map<K, Integer>."
            );
        }
    }

    protected static <T> ConnectionImpl<T> createPagedResult(List<Pair<String, T>> dbResult, int pageSize, Integer totalCount) {
        var items = dbResult.subList(0, Math.min(dbResult.size(), pageSize));

        List<Edge<T>> edges = items
                .stream()
                .map(item -> new DefaultEdge<>(item != null ? item.getRight() : null, new DefaultConnectionCursor(item != null ? item.getLeft() : null)))
                .collect(Collectors.toList());

        return ConnectionImpl
                .<T>builder()
                .setPageInfo(getDefaultPageInfo(dbResult.size() > pageSize, items))
                .setNodes(items.stream().map(Pair::getRight).toList())
                .setEdges(edges)
                .setTotalCount(totalCount)
                .build();
    }

    private static <T> DefaultPageInfo getDefaultPageInfo(boolean hasNextPage, List<Pair<String, T>> items) {
        if (items.isEmpty()) {
            return new DefaultPageInfo(null, null, false, hasNextPage);
        }

        var itemStart = items.get(0);
        var itemEnd = items.get(items.size() - 1);
        return new DefaultPageInfo(
                new DefaultConnectionCursor(itemStart != null ? itemStart.getLeft() : null),
                new DefaultConnectionCursor(itemEnd != null ? itemEnd.getLeft() : null),
                false,
                hasNextPage
        );
    }
}
