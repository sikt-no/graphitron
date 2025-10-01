package fake.code.generated.resolvers.query;

import fake.code.generated.queries.QueryQueryDBQueries;
import fake.graphql.example.model.DummyConnection;
import fake.graphql.example.model.DummyConnectionEdge;
import fake.graphql.example.model.PageInfo;
import graphql.schema.DataFetcher;
import java.lang.Integer;
import java.lang.String;
import java.util.concurrent.CompletableFuture;

import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

public class QueryQueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<DummyConnection>> query() {
        return env -> {
            Integer first = env.getArgument("first");
            String after = env.getArgument("after");
            int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
            return new DataFetcherHelper(env).loadPaginated(
                    pageSize, 1000,
                    (ctx, selectionSet) -> QueryQueryDBQueries.queryForQuery(ctx, pageSize,after, selectionSet),
                    (ctx, resolverKeys) -> QueryQueryDBQueries.countQueryForQuery(ctx),
                    (connection) ->  {
                        var edges = connection.getEdges().stream().map(it -> new DummyConnectionEdge(it.getCursor() == null ? null : it.getCursor().getValue(), it.getNode())).toList();
                        var page = connection.getPageInfo();
                        var graphPage = new PageInfo(page.isHasPreviousPage(), page.isHasNextPage(), page.getStartCursor() == null ? null : page.getStartCursor().getValue(), page.getEndCursor() == null ? null : page.getEndCursor().getValue());
                        return new DummyConnection(edges, graphPage, connection.getNodes(), connection.getTotalCount());
                    }
            );
        };
    }
}
