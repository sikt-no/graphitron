package fake.code.generated.resolvers.query;

import fake.code.generated.queries.WrapperDBQueries;
import fake.graphql.example.api.WrapperResolver;
import fake.graphql.example.model.DummyEnum;
import fake.graphql.example.model.DummyInput;
import fake.graphql.example.model.DummyType;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Boolean;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class WrapperGeneratedResolver implements WrapperResolver {
    @Override
    public CompletableFuture<DummyType> query(Wrapper wrapper, String id, String str, Boolean bool,
                                              Integer i, DummyEnum e, DummyInput in, List<String> idList, List<DummyInput> inList,
                                              DataFetchingEnvironment env) throws Exception {
        return new DataFetcherHelper(env).load(
                wrapper.getQueryKey(),
                (ctx, resolverKeys, selectionSet) -> WrapperDBQueries.queryForWrapper(ctx, resolverKeys, id, str, bool, i, e, in, idList, inList, selectionSet));
    }

    @Override
    public CompletableFuture<DummyType> queryNonNullable(Wrapper wrapper, String id, String str,
                                                         Boolean bool, Integer i, DummyEnum e, DummyInput in, List<String> idList,
                                                         List<DummyInput> inList, DataFetchingEnvironment env) throws Exception {
        return new DataFetcherHelper(env).load(
                wrapper.getQueryNonNullableKey(),
                (ctx, resolverKeys, selectionSet) -> WrapperDBQueries.queryNonNullableForWrapper(ctx, resolverKeys, id, str, bool, i, e, in, idList, inList, selectionSet));
    }
}
