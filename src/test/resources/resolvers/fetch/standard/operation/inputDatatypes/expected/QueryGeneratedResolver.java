package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.DummyEnum;
import fake.graphql.example.model.DummyInput;
import fake.graphql.example.model.DummyType;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Boolean;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;

public class QueryGeneratedResolver implements QueryResolver {
    @Override
    public CompletableFuture<DummyType> query(String id, String str, Boolean bool, Integer i,
                                              DummyEnum e, DummyInput in, List<String> idList, List<DummyInput> inList,
                                              DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env).load(
                (ctx, selectionSet) -> QueryDBQueries.queryForQuery(ctx, id, str, bool, i, e, in, idList, inList, selectionSet));
    }

    @Override
    public CompletableFuture<DummyType> queryNonNullable(String id, String str, Boolean bool,
                                                         Integer i, DummyEnum e, DummyInput in, List<String> idList, List<DummyInput> inList,
                                                         DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env).load(
                (ctx, selectionSet) -> QueryDBQueries.queryNonNullableForQuery(ctx, id, str, bool, i, e, in, idList, inList, selectionSet));
    }
}
