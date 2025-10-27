package fake.code.generated.resolvers.query;

import fake.code.generated.queries.QueryDBQueries;
import fake.graphql.example.model.DummyType;
import graphql.schema.DataFetcher;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<DummyType>> query() {
        return _iv_env -> {
            return new DataFetcherHelper(_iv_env).load((_iv_ctx, _iv_selectionSet) -> QueryDBQueries.queryForQuery(_iv_ctx, _iv_selectionSet));
        };
    }
}
