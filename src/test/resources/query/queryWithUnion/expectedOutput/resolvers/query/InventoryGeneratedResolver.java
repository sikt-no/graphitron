package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.InventoryDBQueries;
import fake.graphql.example.api.InventoryResolver;
import fake.graphql.example.model.Film2;
import fake.graphql.example.model.Inventory;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.List;
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
    public CompletableFuture<List<Film2>> films(Inventory inventory, DataFetchingEnvironment env)
            throws Exception {
        return new DataFetcher(env, this.ctx).loadNonNullable("filmsForInventory", inventory.getId(), (ctx, ids, selectionSet) -> inventoryDBQueries.filmsForInventory(ctx, ids, selectionSet));
    }
}