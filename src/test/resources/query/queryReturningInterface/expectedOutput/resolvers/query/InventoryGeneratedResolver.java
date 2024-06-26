package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.InventoryDBQueries;
import fake.graphql.example.api.InventoryResolver;
import fake.graphql.example.model.Inventory;
import fake.graphql.example.model.Rental;
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

    @Override
    public CompletableFuture<List<Rental>> rentals(Inventory inventory, DataFetchingEnvironment env)
            throws Exception {
        return new DataFetcher(env, this.ctx).loadNonNullable("rentalsForInventory", inventory.getId(), (ctx, ids, selectionSet) -> InventoryDBQueries.rentalsForInventory(ctx, ids, selectionSet));
    }
}
