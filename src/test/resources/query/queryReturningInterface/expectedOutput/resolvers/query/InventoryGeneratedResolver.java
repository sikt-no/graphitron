package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.InventoryDBQueries;
import fake.graphql.example.api.InventoryResolver;
import fake.graphql.example.model.Inventory;
import fake.graphql.example.model.Rental;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.dataloader.DataLoader;
import org.jooq.DSLContext;

public class InventoryGeneratedResolver implements InventoryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private InventoryDBQueries inventoryDBQueries;

    @Override
    public CompletableFuture<List<Rental>> rentals(Inventory inventory, DataFetchingEnvironment env)
            throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, List<Rental>> loader = DataLoaders.getDataLoader(env, "rentalsForInventory", (ids, selectionSet) -> inventoryDBQueries.rentalsForInventory(ctx, ids, selectionSet));
        return DataLoaders.loadNonNullable(loader, inventory.getId(), env);
    }
}