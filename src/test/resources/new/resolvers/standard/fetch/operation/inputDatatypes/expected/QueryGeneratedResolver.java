package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Enum;
import fake.graphql.example.model.Input;
import fake.graphql.example.model.Target;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Boolean;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Target> query(String id, String str, Boolean bool, Integer i, Enum e,
                                           Input in, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.queryForQuery(ctx, id, str, bool, i, e, in, selectionSet));
    }

    @Override
    public CompletableFuture<Target> queryNonNullable(String id, String str, Boolean bool,
                                                      Integer i, Enum e, Input in, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.queryNonNullableForQuery(ctx, id, str, bool, i, e, in, selectionSet));
    }
}
