package fake.code.generated.resolvers.query;

import fake.code.generated.queries.QueryDBQueries;
import fake.graphql.example.model.DummyType;
import graphql.schema.DataFetcher;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<DummyType>> query() {
        return _iv_env -> {
            List<String> id = _iv_env.getArgument("id");
            var keys = List.of(id);
            return new DataFetcherHelper(_iv_env).loadLookup(keys, (_iv_ctx, _iv_keys, _iv_selectionSet) -> QueryDBQueries.queryForQuery(_iv_ctx, id, _iv_selectionSet));
        };
    }
}
