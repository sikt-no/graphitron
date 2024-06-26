package fake.code.generated.resolvers.query;

import fake.code.generated.api.QueryResolver;
import fake.code.generated.model.Film;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.Integer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import fake.code.generated.queries.query.QueryDBQueries;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<Film>> film(Integer releaseYear, DataFetchingEnvironment env)
            throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.filmForQuery(ctx, releaseYear, selectionSet));
    }
}
