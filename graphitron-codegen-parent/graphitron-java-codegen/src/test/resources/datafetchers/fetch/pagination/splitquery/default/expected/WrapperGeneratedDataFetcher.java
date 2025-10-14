package fake.code.generated.resolvers.query;

import fake.code.generated.queries.WrapperDBQueries;
import fake.graphql.example.model.DummyType;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetcher;
import java.lang.Integer;
import java.lang.String;
import java.util.concurrent.CompletableFuture;

import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphql.relay.ConnectionImpl;

public class WrapperGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<ConnectionImpl<DummyType>>> query() {
        return env -> {
            Wrapper wrapper = env.getSource();
            Integer first = env.getArgument("first");
            String after = env.getArgument("after");
            int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
            return new DataFetcherHelper(env).loadPaginated(
                    wrapper.getQueryKey(), pageSize,
                    (ctx, resolverKeys, selectionSet) -> WrapperDBQueries.queryForWrapper(ctx, resolverKeys, pageSize, after, selectionSet),
                    (ctx, resolverKeys) -> WrapperDBQueries.countQueryForWrapper(ctx, resolverKeys)
            );
        };
    }
}
