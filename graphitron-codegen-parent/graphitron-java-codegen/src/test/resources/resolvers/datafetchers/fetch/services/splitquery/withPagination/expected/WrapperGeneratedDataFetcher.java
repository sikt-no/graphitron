package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerConnection;
import fake.graphql.example.model.CustomerConnectionEdge;
import fake.graphql.example.model.PageInfo;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetcher;
import java.lang.Integer;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import no.sikt.graphitron.codereferences.services.ResolverFetchService;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphql.helpers.resolvers.ServiceDataFetcherHelper;

public class WrapperGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<CustomerConnection>> query() {
        return env -> {
            var _args = env.getArguments();
            var wrapper = ((Wrapper) env.getSource());
            var first = ((Integer) _args.get("first"));
            var after = ((String) _args.get("after"));
            int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
            var transform = new RecordTransformer(env);
            var resolverFetchService = new ResolverFetchService(transform.getCtx());
            return new ServiceDataFetcherHelper<>(transform).loadPaginated(
                    wrapper.getQueryKey(), pageSize, 1000,
                    (resolverKeys) -> resolverFetchService.queryMap(resolverKeys, pageSize, after),
                    (resolverKeys) -> resolverFetchService.countQueryMap(resolverKeys),
                    (recordTransform, response) -> recordTransform.customerTableRecordToGraphType(response, ""),
                    (connection) ->  {
                        var edges = connection.getEdges().stream().map(it -> new CustomerConnectionEdge(it.getCursor() == null ? null : it.getCursor().getValue(), it.getNode())).collect(Collectors.toList());
                        var page = connection.getPageInfo();
                        var graphPage = new PageInfo(page.isHasPreviousPage(), page.isHasNextPage(), page.getStartCursor() == null ? null : page.getStartCursor().getValue(), page.getEndCursor() == null ? null : page.getEndCursor().getValue());
                        return new CustomerConnection(edges, graphPage, connection.getNodes(), connection.getTotalCount());
                    }
            );
        };
    }
}
