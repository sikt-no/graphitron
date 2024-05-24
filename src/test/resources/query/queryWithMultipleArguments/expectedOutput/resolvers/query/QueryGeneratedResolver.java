package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Film;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;

import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<ExtendedConnection<Film>> filmTwoArguments(String releaseYear,
            List<Integer> languageID, Integer first, String after, DataFetchingEnvironment env)
            throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).load(pageSize, 1000,
                (ctx, selectionSet) -> queryDBQueries.filmTwoArgumentsForQuery(ctx, releaseYear, languageID, pageSize, after, selectionSet),
                (ctx, ids, selectionSet) -> selectionSet.contains("totalCount") ? queryDBQueries.countFilmTwoArgumentsForQuery(ctx, releaseYear, languageID) : null,
                (it) -> it.getId());
    }

    @Override
    public CompletableFuture<ExtendedConnection<Film>> filmFiveArguments(String releaseYear,
            List<Integer> languageID, String description, String title, Integer length,
            Integer first, String after, DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).load(pageSize, 1000,
                (ctx, selectionSet) -> queryDBQueries.filmFiveArgumentsForQuery(ctx, releaseYear, languageID, description, title, length, pageSize, after, selectionSet),
                (ctx, ids, selectionSet) -> selectionSet.contains("totalCount") ? queryDBQueries.countFilmFiveArgumentsForQuery(ctx, releaseYear, languageID, description, title, length) : null,
                (it) -> it.getId());
    }
}