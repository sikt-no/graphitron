package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.DummyType;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;

public class QueryGeneratedResolver implements QueryResolver {
    @Override
    public CompletableFuture<DummyType> query(List<String> id, DataFetchingEnvironment env) throws
            Exception {
        var keys = List.of(id);
        return new DataFetcher(env).loadLookup(keys, (ctx, ids, selectionSet) -> QueryDBQueries.queryForQuery(ctx, id, selectionSet));
    }
}
