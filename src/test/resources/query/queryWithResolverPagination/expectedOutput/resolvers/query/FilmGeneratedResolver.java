package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.FilmDBQueries;
import fake.graphql.example.api.FilmResolver;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.Inventory;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;

import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;
import org.jooq.DSLContext;

public class FilmGeneratedResolver implements FilmResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private FilmDBQueries filmDBQueries;

    @Override
    public CompletableFuture<ExtendedConnection<Inventory>> inventory(Film film, Integer first,
            String after, DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 10);
        return new DataFetcher(env, this.ctx).load("inventoryForFilm", film.getId(), pageSize, 1000,
                (ctx, ids, selectionSet) -> filmDBQueries.inventoryForFilm(ctx, ids, pageSize, after, selectionSet),
                (ctx, ids, selectionSet) -> selectionSet.contains("totalCount") ? filmDBQueries.countInventoryForFilm(ctx, ids) : null,
                (it) -> it.getId());
    }
}
