package fake.code.generated.resolvers.query;

import fake.code.generated.queries.QueryQueryDBQueries;
import fake.graphql.example.model.DummyType;
import graphql.schema.DataFetcher;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryQueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<DummyType>> query() {
        return env -> {
            return new DataFetcherHelper(env).load((ctx, selectionSet) -> QueryQueryDBQueries.queryForQuery(ctx, selectionSet));
        };
    }
}
