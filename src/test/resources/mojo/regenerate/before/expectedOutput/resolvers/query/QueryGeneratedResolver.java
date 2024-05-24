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
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import fake.code.generated.queries.query.QueryDBQueries;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<List<Film>> film(Integer releaseYear, DataFetchingEnvironment env)
            throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var selectionSet = ResolverHelpers.getSelectionSet(env);
        return CompletableFuture.completedFuture(queryDBQueries.filmForQuery(ctx, releaseYear, selectionSet));
    }
}
