package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.WrapperDBQueries;
import fake.graphql.example.api.WrapperResolver;
import fake.graphql.example.model.DummyType;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class WrapperGeneratedResolver implements WrapperResolver {
    @Override
    public CompletableFuture<DummyType> query(Wrapper wrapper, List<String> id,
                                              DataFetchingEnvironment env) throws Exception {
        var keys = List.of(id);
        return new DataFetcherHelper(env).loadLookup(keys, (ctx, ids, selectionSet) -> WrapperDBQueries.queryForWrapper(ctx, id, selectionSet));
    }
}
