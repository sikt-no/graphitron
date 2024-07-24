package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.WrapperDBQueries;
import fake.graphql.example.api.WrapperResolver;
import fake.graphql.example.model.Enum;
import fake.graphql.example.model.Input;
import fake.graphql.example.model.Target;
import fake.graphql.example.model.Wrapper;
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

public class WrapperGeneratedResolver implements WrapperResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Target> query(Wrapper wrapper, String id, String str, Boolean bool,
                                           Integer i, Enum e, Input in, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load(
                "queryForWrapper", wrapper.getId(),
                (ctx, ids, selectionSet) -> WrapperDBQueries.queryForWrapper(ctx, ids, id, str, bool, i, e, in, selectionSet));
    }

    @Override
    public CompletableFuture<Target> queryNonNullable(Wrapper wrapper, String id, String str,
                                                      Boolean bool, Integer i, Enum e, Input in, DataFetchingEnvironment env) throws
            Exception {
        return new DataFetcher(env, this.ctx).load(
                "queryNonNullableForWrapper", wrapper.getId(),
                (ctx, ids, selectionSet) -> WrapperDBQueries.queryNonNullableForWrapper(ctx, ids, id, str, bool, i, e, in, selectionSet));
    }
}
