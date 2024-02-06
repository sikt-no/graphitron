package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.InventoryDBQueries;
import fake.graphql.example.api.InventoryResolver;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.Inventory;
import fake.graphql.example.model.Store;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import org.dataloader.DataLoader;
import org.jooq.DSLContext;

public class InventoryGeneratedResolver implements InventoryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private InventoryDBQueries inventoryDBQueries;

    @Override
    public CompletableFuture<Store> store(Inventory inventory, DataFetchingEnvironment env) throws
            Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, Store> loader = DataLoaders.getDataLoader(env, "storeForInventory", (ids, selectionSet) -> inventoryDBQueries.storeForInventory(ctx, ids, selectionSet));
        return DataLoaders.load(loader, inventory.getId(), env);
    }

    @Override
    public CompletableFuture<Film> film(Inventory inventory, DataFetchingEnvironment env) throws
            Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, Film> loader = DataLoaders.getDataLoader(env, "filmForInventory", (ids, selectionSet) -> inventoryDBQueries.filmForInventory(ctx, ids, selectionSet));
        return DataLoaders.load(loader, inventory.getId(), env);
    }
}
