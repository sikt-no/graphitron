package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.AddressConnection;
import fake.graphql.example.model.AddressConnectionEdge;
import fake.graphql.example.model.PageInfo;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import no.sikt.graphql.helpers.resolvers.DataFetcher;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

public class QueryGeneratedResolver implements QueryResolver {
    @Override
    public CompletableFuture<AddressConnection> address(Integer first, String after,
                                                        DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env).loadPaginated(
                pageSize, 1000,
                (ctx, selectionSet) -> QueryDBQueries.addressForQuery(ctx, pageSize,after, selectionSet),
                (ctx, ids) -> QueryDBQueries.countAddressForQuery(ctx),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> AddressConnectionEdge.builder().setCursor(it.getCursor() == null ? null : it.getCursor().getValue()).setNode(it.getNode()).build()).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = PageInfo.builder().setStartCursor(page.getStartCursor() == null ? null : page.getStartCursor().getValue()).setEndCursor(page.getEndCursor() == null ? null : page.getEndCursor().getValue()).setHasNextPage(page.isHasNextPage()).setHasPreviousPage(page.isHasPreviousPage()).build();
                    return AddressConnection.builder().setNodes(connection.getNodes()).setEdges(edges).setTotalCount(connection.getTotalCount()).setPageInfo(graphPage).build();
                }
        );
    }
}
