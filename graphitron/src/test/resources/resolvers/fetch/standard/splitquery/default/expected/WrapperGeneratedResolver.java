package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.WrapperDBQueries;
import fake.graphql.example.api.WrapperResolver;
import fake.graphql.example.model.DummyType;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcher;

public class WrapperGeneratedResolver implements WrapperResolver {
    @Override
    public CompletableFuture<DummyType> query(Wrapper wrapper, DataFetchingEnvironment env) throws
            Exception {
        return new DataFetcher(env).load("queryForWrapper", wrapper.getId(), (ctx, ids, selectionSet) -> WrapperDBQueries.queryForWrapper(ctx, ids, selectionSet));
    }
}
