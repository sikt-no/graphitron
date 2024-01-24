package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.RentalDBQueries;
import fake.graphql.example.package.api.RentalResolver;
import fake.graphql.example.package.model.Inventory;
import fake.graphql.example.package.model.Rental;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import javax.inject.Inject;
import org.dataloader.DataLoader;
import org.jooq.DSLContext;

public class RentalGeneratedResolver implements RentalResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private RentalDBQueries rentalDBQueries;

    @Override
    public CompletableFuture<Inventory> inventory(Rental rental, DataFetchingEnvironment env) throws
            Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, Inventory> loader = DataLoaders.getDataLoader(env, "inventoryForRental", (ids, selectionSet) -> rentalDBQueries.inventoryForRental(ctx, ids, selectionSet));
        return DataLoaders.load(loader, rental.getId(), env);
    }
}