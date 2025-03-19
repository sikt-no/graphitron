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

    protected <K, V> DataLoader<KeyWithPath<K>, V> getLoader(String resolveName, DataLoaderMapper<KeyWithPath<K>, V> mapFunction) {
        return env.getDataLoaderRegistry().computeIfAbsent(resolveName, name ->
                DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<KeyWithPath<K>, V>) (keys, batchEnvLoader) ->
                        mapFunction.map(keys, new SelectionSet(getSelectionSetsFromEnvironment(batchEnvLoader)))
                )
        );
    }

    protected <K, V> DataLoader<KeyWithPath<K>, V> getConnectionLoader(String resolveName, DataLoaderMapper<KeyWithPath<K>, V> mapFunction) {
        return env.getDataLoaderRegistry().computeIfAbsent(resolveName, name ->
                DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<KeyWithPath<K>, V>) (keys, batchEnvLoader) ->
                        mapFunction.map(keys, new ConnectionSelectionSet(getSelectionSetsFromEnvironment(batchEnvLoader)))
                )
        );
    }

    protected static <K, V> Map<KeyWithPath<K>, V> resultAsMap(Map<KeyWithPath<K>, String> keyToId, Map<String, V> dbResult) {
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
                                    return dbValue instanceof List<?> ? (V)((List<?>)dbValue).stream().filter(Objects::nonNull).toList() : dbValue;
                                }
                        )
                );
    }

    protected static <K> Map<KeyWithPath<K>, String> getKeyToId(Set<KeyWithPath<K>> keys) {
        return keys.stream().collect(Collectors.toMap(s -> s, s -> s.toString().substring(s.toString().lastIndexOf("||") + 2)));
    }

    protected static <T, C> C getPaginatedConnection(List<Pair<String, T>> dbResult, int pageSize, Integer totalCount, int maxNodes, Function<ConnectionImpl<T>, C> connectionFunction) {
        return connectionFunction.apply(createPagedResult(dbResult, pageSize, totalCount != null ? Math.min(maxNodes, totalCount) : null));
    }

    protected static <K, V, C> Map<KeyWithPath<K>, C> getPaginatedConnection(Map<KeyWithPath<K>, List<Pair<String, V>>> dbResult, int pageSize, Integer totalCount, int maxNodes, Function<ConnectionImpl<V>, C> connectionFunction) {
        return dbResult.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> connectionFunction.apply(createPagedResult(entry.getValue(), pageSize, totalCount != null ? Math.min(maxNodes, totalCount) : null))));
    }

    protected static <T> ConnectionImpl<T> createPagedResult(List<Pair<String, T>> dbResult, int pageSize, Integer totalCount) {
        var items = dbResult.subList(0, Math.min(dbResult.size(), pageSize));
        var pageInfo = getDefaultPageInfo(dbResult, pageSize, items);

        List<Edge<T>> edges = items
                .stream()
                .map(item -> new DefaultEdge<>(item != null ? item.getRight() : null, new DefaultConnectionCursor(item != null ? item.getLeft() : null)))
                .collect(Collectors.toList());

        return ConnectionImpl
                .<T>builder()
                .setPageInfo(pageInfo)
                .setNodes(items.stream().map(Pair::getRight).toList())
                .setEdges(edges)
                .setTotalCount(totalCount)
                .build();
    }

    private static <T> DefaultPageInfo getDefaultPageInfo(List<Pair<String, T>> dbResult, int pageSize, List<Pair<String, T>> items) {
        if (items.isEmpty()) {
            return new DefaultPageInfo(null, null, false, dbResult.size() > pageSize);
        }

        var itemStart = items.get(0);
        var itemEnd = items.get(items.size() - 1);
        return new DefaultPageInfo(
                new DefaultConnectionCursor(itemStart != null ? itemStart.getLeft() : null),
                new DefaultConnectionCursor(itemEnd != null ? itemEnd.getLeft() : null),
                false,
                dbResult.size() > pageSize
        );
    }
}
