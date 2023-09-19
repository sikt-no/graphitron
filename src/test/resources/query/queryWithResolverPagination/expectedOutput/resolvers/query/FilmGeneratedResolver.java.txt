package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.FilmDBQueries;
import fake.graphql.example.package.api.FilmResolver;
import fake.graphql.example.package.model.Film;
import fake.graphql.example.package.model.Inventory;
import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Math;
import java.lang.Override;
import java.lang.String;
import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.selection.ConnectionSelectionSet;
import no.fellesstudentsystem.graphql.relay.ConnectionImpl;
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;
import org.jooq.DSLContext;

public class FilmGeneratedResolver implements FilmResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private FilmDBQueries filmDBQueries;

    @Override
    public CompletableFuture<ExtendedConnection<Inventory>> inventory(Film film, Integer first,
            String after, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        int pageSize = Optional.ofNullable(first).orElse(10);
        DataLoader<String, ExtendedConnection<Inventory>> loader = env.getDataLoaderRegistry().computeIfAbsent("inventoryForFilm", name -> {
            var batchLoader = (MappedBatchLoaderWithContext<String, ExtendedConnection<Inventory>>) (keys, batchEnvLoader) -> {
                var keyToId = keys.stream().collect(
                        Collectors.toMap(s -> s, s -> s.substring(s.lastIndexOf("||") + 2)));
                var idSet = new HashSet<>(keyToId.values());
                var selectionSet = new ConnectionSelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader));
                var dbResult = filmDBQueries.inventoryForFilm(ctx, idSet, pageSize, after, selectionSet);
                var totalCount = selectionSet.contains("totalCount") ? filmDBQueries.countInventoryForFilm(ctx, idSet) : null;
                var mapResult = keyToId.entrySet().stream()
                        .filter(it -> dbResult.get(it.getValue()) != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, it -> dbResult.get(it.getValue())));
                var pagedResult = mapResult.entrySet().stream().map(resultEntry -> {
                    var resultValue = resultEntry.getValue();
                    var size = Math.min(resultValue.size(), pageSize);
                    var items = resultValue.subList(0, size);
                    var firstItem = items.size() == 0 ? null : new DefaultConnectionCursor(items.get(0).getId());
                    var lastItem = items.size() == 0 ? null : new DefaultConnectionCursor(items.get(items.size() - 1).getId());
                    var pagedResultEntry = ConnectionImpl
                            .<Inventory>builder()
                            .setPageInfo(
                                    new DefaultPageInfo(firstItem, lastItem, false, resultValue.size() > pageSize)
                            )
                            .setNodes(items)
                            .setEdges(
                                    items
                                            .stream()
                                            .map(item -> new DefaultEdge<Inventory>(item, new DefaultConnectionCursor(item.getId())))
                                            .collect(Collectors.toList())
                            )
                            .setTotalCount(totalCount != null ? Math.min(1000, totalCount) : null)
                            .build();
                    return new AbstractMap.SimpleEntry<String, ExtendedConnection<Inventory>>(resultEntry.getKey(), pagedResultEntry);
                } ).collect(Collectors.toMap(r -> r.getKey(), r -> r.getValue()));
                return CompletableFuture.completedFuture(pagedResult);
            } ;
            return DataLoaderFactory.newMappedDataLoader(batchLoader);
        } );
        return loader.load(env.getExecutionStepInfo().getPath().toString() + "||" + film.getId(), env);
    }
}