package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.package.api.QueryResolver;
import fake.graphql.example.package.model.Film;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<ExtendedConnection<Film>> films(String releaseYear, Integer first,
            String after, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return DataLoaders.loadData(env, pageSize, 1000, (selectionSet) -> queryDBQueries.filmsForQuery(ctx, releaseYear, pageSize, after, selectionSet), (ids, selectionSet) -> selectionSet.contains("totalCount") ? queryDBQueries.countFilmsForQuery(ctx, releaseYear) : null, (it) -> it.getId());
    }
}