package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.InventoryDBQueries;
import fake.graphql.example.package.api.InventoryResolver;
import fake.graphql.example.package.model.Actor;
import fake.graphql.example.package.model.Inventory;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;
import org.jooq.DSLContext;

public class InventoryGeneratedResolver implements InventoryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private InventoryDBQueries inventoryDBQueries;

    @Override
    public CompletableFuture<List<Actor>> mainActors(Inventory inventory,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        DataLoader<String, List<Actor>> loader = env.getDataLoaderRegistry().computeIfAbsent("mainActorsForInventory", name -> {
            var batchLoader = (MappedBatchLoaderWithContext<String, List<Actor>>) (keys, batchEnvLoader) -> {
                var keyToId = keys.stream().collect(
                        Collectors.toMap(s -> s, s -> s.substring(s.lastIndexOf("||") + 2)));
                var idSet = new HashSet<>(keyToId.values());
                var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader));
                var dbResult = inventoryDBQueries.mainActorsForInventory(ctx, idSet, selectionSet);
                var mapResult = keyToId.entrySet().stream()
                        .filter(it -> dbResult.get(it.getValue()) != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, it -> dbResult.get(it.getValue())));
                return CompletableFuture.completedFuture(mapResult);
            } ;
            return DataLoaderFactory.newMappedDataLoader(batchLoader);
        } );
        return loader.load(env.getExecutionStepInfo().getPath().toString() + "||" + inventory.getId(), env).thenApply(data -> Optional.ofNullable(data).orElse(List.of()));
    }
}