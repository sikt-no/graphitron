package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.PageInfo;
import fake.graphql.example.model.DummyConnection;
import fake.graphql.example.model.DummyConnectionEdge;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

public class QueryGeneratedResolver implements QueryResolver {
    @Override
    public CompletableFuture<DummyConnection> query(Integer first, String after,
                                                     DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcherHelper(env).loadPaginated(
                pageSize, 1000,
                (ctx, selectionSet) -> QueryDBQueries.queryForQuery(ctx, pageSize,after, selectionSet),
                (ctx, ids) -> QueryDBQueries.countQueryForQuery(ctx),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> DummyConnectionEdge.builder().setCursor(it.getCursor() == null ? null : it.getCursor().getValue()).setNode(it.getNode()).build()).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = PageInfo.builder().setStartCursor(page.getStartCursor() == null ? null : page.getStartCursor().getValue()).setEndCursor(page.getEndCursor() == null ? null : page.getEndCursor().getValue()).setHasNextPage(page.isHasNextPage()).setHasPreviousPage(page.isHasPreviousPage()).build();
                    return DummyConnection.builder().setNodes(connection.getNodes()).setEdges(edges).setTotalCount(connection.getTotalCount()).setPageInfo(graphPage).build();
                }
        );
    }
}
