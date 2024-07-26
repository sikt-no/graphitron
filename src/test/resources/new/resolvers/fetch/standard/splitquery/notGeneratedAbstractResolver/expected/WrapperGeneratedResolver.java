package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.WrapperDBQueries;
import fake.graphql.example.api.WrapperResolver;
import fake.graphql.example.model.DummyType;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public abstract class WrapperGeneratedResolver implements WrapperResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<DummyType> query1(Wrapper wrapper, DataFetchingEnvironment env) throws
            Exception {
        return new DataFetcher(env, this.ctx).load("query1ForWrapper", wrapper.getId(), (ctx, ids, selectionSet) -> WrapperDBQueries.query1ForWrapper(ctx, ids, selectionSet));
    }
}
