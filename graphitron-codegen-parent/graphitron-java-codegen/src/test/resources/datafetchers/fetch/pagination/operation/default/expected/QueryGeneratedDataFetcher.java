package fake.code.generated.resolvers.query;

import fake.code.generated.queries.QueryDBQueries;
import fake.graphql.example.model.DummyType;
import graphql.schema.DataFetcher;
import java.lang.Integer;
import java.lang.String;
import java.util.concurrent.CompletableFuture;

import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphql.relay.ConnectionImpl;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<ConnectionImpl<DummyType>>> query() {
        return env -> {
            Integer first = env.getArgument("first");
            String after = env.getArgument("after");
            int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
            return new DataFetcherHelper(env).loadPaginated(
                    pageSize,
                    (ctx, selectionSet) -> QueryDBQueries.queryForQuery(ctx, pageSize,after, selectionSet),
                    (ctx, resolverKeys) -> QueryDBQueries.countQueryForQuery(ctx)
            );
        };
    }
}
