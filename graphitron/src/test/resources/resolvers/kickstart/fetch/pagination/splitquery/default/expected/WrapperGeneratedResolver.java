package fake.code.generated.resolvers.query;

import fake.code.generated.queries.WrapperDBQueries;
import fake.graphql.example.api.WrapperResolver;
import fake.graphql.example.model.PageInfo;
import fake.graphql.example.model.DummyConnection;
import fake.graphql.example.model.DummyConnectionEdge;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

public class WrapperGeneratedResolver implements WrapperResolver {
    @Override
    public CompletableFuture<DummyConnection> query(Wrapper wrapper, Integer first, String after,
                                                     DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcherHelper(env).loadPaginated(
                wrapper.getId(), pageSize, 1000,
                (ctx, ids, selectionSet) -> WrapperDBQueries.queryForWrapper(ctx, ids, pageSize,after, selectionSet),
                (ctx, ids) -> WrapperDBQueries.countQueryForWrapper(ctx, ids),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> new DummyConnectionEdge(it.getCursor() == null ? null : it.getCursor().getValue(), it.getNode())).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = new PageInfo(page.isHasPreviousPage(), page.isHasNextPage(), page.getStartCursor() == null ? null : page.getStartCursor().getValue(), page.getEndCursor() == null ? null : page.getEndCursor().getValue());
                    return new DummyConnection(edges, graphPage, connection.getNodes(), connection.getTotalCount());
                }
        );
    }
}
