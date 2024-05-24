package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.InventoryDBQueries;
import fake.graphql.example.api.InventoryResolver;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.Inventory;
import fake.graphql.example.model.Store;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class InventoryGeneratedResolver implements InventoryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private InventoryDBQueries inventoryDBQueries;

    @Override
    public CompletableFuture<Store> store(Inventory inventory, DataFetchingEnvironment env) throws
            Exception {
        return new DataFetcher(env, this.ctx).load("storeForInventory", inventory.getId(), (ctx, ids, selectionSet) -> inventoryDBQueries.storeForInventory(ctx, ids, selectionSet));
    }

    @Override
    public CompletableFuture<Film> film(Inventory inventory, DataFetchingEnvironment env) throws
            Exception {
        return new DataFetcher(env, this.ctx).load("filmForInventory", inventory.getId(), (ctx, ids, selectionSet) -> inventoryDBQueries.filmForInventory(ctx, ids, selectionSet));
    }
}
