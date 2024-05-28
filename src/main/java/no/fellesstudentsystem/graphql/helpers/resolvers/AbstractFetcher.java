package no.fellesstudentsystem.graphql.helpers.resolvers;

import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import graphql.relay.Edge;
import graphql.schema.DataFetchingEnvironment;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.functions.DataLoaderMapper;
import no.fellesstudentsystem.graphql.helpers.selection.ConnectionSelectionSet;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.relay.ConnectionImpl;
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;
import org.jooq.DSLContext;

import java.util.*;
import java.util.concurrent.CompletableFuture;
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
    protected AbstractFetcher(DataFetchingEnvironment env, DSLContext ctx) {
        this.env = env;
        this.ctx = ResolverHelpers.selectContext(env, ctx);

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

    protected <T> CompletableFuture<ExtendedConnection<T>> getPaginatedConnection(List<T> dbResult, int pageSize, Integer totalCount, int maxNodes, Function<T, String> idFunction) {
        return CompletableFuture.completedFuture(createPagedResult(dbResult, pageSize, totalCount != null ? Math.min(maxNodes, totalCount) : null, idFunction));
    }

    protected <T> CompletableFuture<Map<String, ExtendedConnection<T>>> getPaginatedConnection(Map<String, List<T>> dbResult, int pageSize, Integer totalCount, int maxNodes, Function<T, String> idFunction) {
        var pagedResult = dbResult.entrySet().stream().map(resultEntry -> {
            var pagedResultEntry = createPagedResult(resultEntry.getValue(), pageSize, totalCount != null ? Math.min(maxNodes, totalCount) : null, idFunction);
            return new AbstractMap.SimpleEntry<String, ExtendedConnection<T>>(resultEntry.getKey(), pagedResultEntry);
        }).collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));
        return CompletableFuture.completedFuture(pagedResult);
    }

    protected <T> ConnectionImpl<T> createPagedResult(List<T> dbResult, int pageSize, Integer totalCount, Function<T, String> idFunction) {
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
