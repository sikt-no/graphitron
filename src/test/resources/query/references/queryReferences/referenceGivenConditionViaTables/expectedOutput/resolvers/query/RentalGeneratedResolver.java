package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.RentalDBQueries;
import fake.graphql.example.api.RentalResolver;
import fake.graphql.example.model.FilmActor;
import fake.graphql.example.model.Rental;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.dataloader.DataLoader;
import org.jooq.DSLContext;

public class RentalGeneratedResolver implements RentalResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private RentalDBQueries rentalDBQueries;

    @Override
    public CompletableFuture<FilmActor> mainActor(Rental rental, DataFetchingEnvironment env) throws
            Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, FilmActor> loader = DataLoaders.getDataLoader(env, "mainActorForRental", (ids, selectionSet) -> rentalDBQueries.mainActorForRental(ctx, ids, selectionSet));
        return DataLoaders.load(loader, rental.getId(), env);
    }
}