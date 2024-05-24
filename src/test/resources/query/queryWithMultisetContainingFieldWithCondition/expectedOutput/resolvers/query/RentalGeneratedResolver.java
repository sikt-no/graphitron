package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.RentalDBQueries;
import fake.graphql.example.api.RentalResolver;
import fake.graphql.example.model.Inventory;
import fake.graphql.example.model.Rental;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;

import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;

import javax.inject.Inject;
import org.jooq.DSLContext;

public class RentalGeneratedResolver implements RentalResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private RentalDBQueries rentalDBQueries;

    @Override
    public CompletableFuture<Inventory> inventory(Rental rental, DataFetchingEnvironment env) throws
            Exception {
        return new DataFetcher(env, this.ctx).load("inventoryForRental", rental.getId(), (ctx, ids, selectionSet) -> rentalDBQueries.inventoryForRental(ctx, ids, selectionSet));
    }
}