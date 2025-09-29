package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerConnection;
import fake.graphql.example.model.CustomerConnectionEdge;
import fake.graphql.example.model.PageInfo;
import graphql.schema.DataFetcher;
import java.lang.Integer;
import java.lang.String;
import java.util.concurrent.CompletableFuture;

import no.sikt.graphitron.codereferences.services.ResolverFetchService;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphql.helpers.resolvers.ServiceDataFetcherHelper;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<CustomerConnection>> query() {
        return env -> {
            Integer first = env.getArgument("first");
            String after = env.getArgument("after");
            int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
            var transform = new RecordTransformer(env);
            var resolverFetchService = new ResolverFetchService(transform.getCtx());
            return new ServiceDataFetcherHelper<>(transform).loadPaginated(
                    pageSize, 1000,
                    () -> resolverFetchService.queryList(pageSize, after),
                    (resolverKeys) -> resolverFetchService.countQueryList(),
                    (recordTransform, response) -> recordTransform.customerTableRecordToGraphType(response, ""),
                    (connection) ->  {
                        var edges = connection.getEdges().stream().map(it -> new CustomerConnectionEdge(it.getCursor() == null ? null : it.getCursor().getValue(), it.getNode())).toList();
                        var page = connection.getPageInfo();
                        var graphPage = new PageInfo(page.isHasPreviousPage(), page.isHasNextPage(), page.getStartCursor() == null ? null : page.getStartCursor().getValue(), page.getEndCursor() == null ? null : page.getEndCursor().getValue());
                        return new CustomerConnection(edges, graphPage, connection.getNodes(), connection.getTotalCount());
                    }
            );
        };
    }
}
