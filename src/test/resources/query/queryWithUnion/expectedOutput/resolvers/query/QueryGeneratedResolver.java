package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
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
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<ExtendedConnection<Film>> films(Integer first, String after,
                                                             DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).load(pageSize, 1000,
                (ctx, selectionSet) -> queryDBQueries.filmsForQuery(ctx, pageSize, after, selectionSet),
                (ctx, ids, selectionSet) -> selectionSet.contains("totalCount") ? queryDBQueries.countFilmsForQuery(ctx) : null,
                (it) -> it.getId());
    }

    @Override
    public CompletableFuture<ExtendedConnection<Inventory>> inventory(Integer first, String after,
                                                                      DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).load(pageSize, 1000,
                (ctx, selectionSet) -> queryDBQueries.inventoryForQuery(ctx, pageSize, after, selectionSet),
                (ctx, ids, selectionSet) -> selectionSet.contains("totalCount") ? queryDBQueries.countInventoryForQuery(ctx) : null,
                (it) -> it.getId());
    }
}
