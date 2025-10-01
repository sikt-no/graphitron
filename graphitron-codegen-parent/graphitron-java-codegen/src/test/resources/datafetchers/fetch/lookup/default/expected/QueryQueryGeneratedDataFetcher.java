package fake.code.generated.resolvers.query;

import fake.code.generated.queries.QueryQueryDBQueries;
import fake.graphql.example.model.DummyType;
import graphql.schema.DataFetcher;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryQueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<DummyType>> query() {
        return env -> {
            List<String> id = env.getArgument("id");
            var keys = List.of(id);
            return new DataFetcherHelper(env).loadLookup(keys, (ctx, resolverKeys, selectionSet) -> QueryQueryDBQueries.queryForQuery(ctx, id, selectionSet));
        };
    }
}
