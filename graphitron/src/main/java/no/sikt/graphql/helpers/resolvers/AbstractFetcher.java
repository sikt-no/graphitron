package no.sikt.graphql.helpers.resolvers;

import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import graphql.relay.Edge;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphql.helpers.EnvironmentUtils;
import no.sikt.graphql.helpers.functions.DataLoaderMapper;
import no.sikt.graphql.helpers.selection.ConnectionSelectionSet;
import no.sikt.graphql.helpers.selection.SelectionSet;
import no.sikt.graphql.relay.ConnectionImpl;
import org.apache.commons.lang3.tuple.Pair;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;
import org.jooq.DSLContext;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class AbstractFetcher {
    protected final DataFetchingEnvironment env;
    protected final String executionPath;
    protected final DSLContext ctx;
    protected final SelectionSet selection, connectionSelection;

    /**
     * Create a dataloader for a resolver.
     * @param env Environment for the resolver.
     * @param ctx Context from the resolver.
     */
    protected AbstractFetcher(DataFetchingEnvironment env) {
        this.env = env;
        this.ctx = env.getLocalContext();

        this.selection = ResolverHelpers.getSelectionSet(env);
        this.connectionSelection = new ConnectionSelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        this.executionPath = env.getExecutionStepInfo().getPath().toString();
    }

    public DataFetchingEnvironment getEnv() {
        return env;
    }

    public DSLContext getCtx() {
        return ctx;
    }

    protected String asKeyPath(String id) {
        return executionPath + "||" + id;
    }

    protected <T> DataLoader<String, T> getLoader(String resolveName, DataLoaderMapper<T> mapFunction) {
        return env.getDataLoaderRegistry().computeIfAbsent(resolveName, name ->
                DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<String, T>) (keys, batchEnvLoader) ->
                        mapFunction.map(keys, new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader)))
                )
        );
    }

    protected <T> DataLoader<String, T> getConnectionLoader(String resolveName, DataLoaderMapper<T> mapFunction) {
        return env.getDataLoaderRegistry().computeIfAbsent(resolveName, name ->
                DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<String, T>) (keys, batchEnvLoader) ->
                        mapFunction.map(keys, new ConnectionSelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader)))
                )
        );
    }

    protected <T> Map<String, T> resultAsMap(Map<String, String> keyToId, Map<String, T> dbResult) {
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

    protected Map<String, String> getKeyToId(Set<String> keys) {
        return keys.stream().collect(Collectors.toMap(s -> s, s -> s.substring(s.lastIndexOf("||") + 2)));
    }

    protected <T, U> U getPaginatedConnection(List<Pair<String, T>> dbResult, int pageSize, Integer totalCount, int maxNodes, Function<ConnectionImpl<T>, U> connectionFunction) {
        return connectionFunction.apply(createPagedResult(dbResult, pageSize, totalCount != null ? Math.min(maxNodes, totalCount) : null));
    }

    protected <T, U> Map<String, U> getPaginatedConnection(Map<String, List<Pair<String, T>>> dbResult, int pageSize, Integer totalCount, int maxNodes, Function<ConnectionImpl<T>, U> connectionFunction) {
        return dbResult.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> connectionFunction.apply(createPagedResult(entry.getValue(), pageSize, totalCount != null ? Math.min(maxNodes, totalCount) : null))));
    }

    protected <T> ConnectionImpl<T> createPagedResult(List<Pair<String, T>> dbResult, int pageSize, Integer totalCount) {
        var items = dbResult.subList(0, Math.min(dbResult.size(), pageSize));
        DefaultPageInfo pageInfo;
        if (items.isEmpty()) {
            pageInfo = new DefaultPageInfo(null, null, false, dbResult.size() > pageSize);
        } else {
            var itemStart = items.get(0);
            var itemEnd = items.get(items.size() - 1);
            pageInfo = new DefaultPageInfo(
                    new DefaultConnectionCursor(itemStart != null ? itemStart.getLeft() : null),
                    new DefaultConnectionCursor(itemEnd != null ? itemEnd.getLeft() : null),
                    false,
                    dbResult.size() > pageSize
            );
        }

        List<Edge<T>> edges = items
                .stream()
                .map(item -> new DefaultEdge<>(item.getRight(), new DefaultConnectionCursor(item != null ? item.getLeft() : null)))
                .collect(Collectors.toList());

        return ConnectionImpl
                .<T>builder()
                .setPageInfo(pageInfo)
                .setNodes(items.stream().map(Pair::getRight).collect(Collectors.toList()))
                .setEdges(edges)
                .setTotalCount(totalCount)
                .build();
    }
}
