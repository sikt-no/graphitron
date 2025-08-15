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
        return env -> {
            var _args = env.getArguments();
            var id = ((List<String>) _args.get("id"));
            var keys = List.of(id);
            return new DataFetcherHelper(env).loadLookup(keys, (ctx, resolverKeys, selectionSet) -> QueryDBQueries.queryForQuery(ctx, id, selectionSet));
        };
    }
}
