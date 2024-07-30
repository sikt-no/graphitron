package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.DummyType;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<DummyType> query(List<Integer> i, DataFetchingEnvironment env) throws
            Exception {
        var keys = List.of(ResolverHelpers.formatString(i));
        return new DataFetcher(env, this.ctx).loadLookup(keys, (ctx, ids, selectionSet) -> QueryDBQueries.queryForQuery(ctx, i, selectionSet));
    }
}
