package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerInput;
import fake.graphql.example.model.Film;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<Customer> customersNoPage(String active, String storeId,
            CustomerInput pin, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> queryDBQueries.customersNoPageForQuery(ctx, active, storeId, pin, selectionSet));
    }

    @Override
    public CompletableFuture<ExtendedConnection<Customer>> customersWithPage(String active,
            List<Integer> storeIds, CustomerInput pin, Integer first, String after,
            DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).loadPaginated(pageSize, 1000,
                (ctx, selectionSet) -> queryDBQueries.customersWithPageForQuery(ctx, active, storeIds, pin, pageSize, after, selectionSet),
                (ctx, ids) -> queryDBQueries.countCustomersWithPageForQuery(ctx, active, storeIds, pin),
                (it) -> it.getId());
    }

    @Override
    public CompletableFuture<ExtendedConnection<Film>> films(String releaseYear, Integer first,
            String after, DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).loadPaginated(pageSize, 1000,
                (ctx, selectionSet) -> queryDBQueries.filmsForQuery(ctx, releaseYear, pageSize, after, selectionSet),
                (ctx, ids) -> queryDBQueries.countFilmsForQuery(ctx, releaseYear),
                (it) -> it.getId());
    }
}