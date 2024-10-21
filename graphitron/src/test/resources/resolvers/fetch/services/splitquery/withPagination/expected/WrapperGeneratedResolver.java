package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.WrapperResolver;
import fake.graphql.example.model.CustomerConnection;
import fake.graphql.example.model.CustomerConnectionEdge;
import fake.graphql.example.model.PageInfo;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphitron.codereferences.services.ResolverFetchService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.helpers.resolvers.ServiceDataFetcher;

public class WrapperGeneratedResolver implements WrapperResolver {
    @Override
    public CompletableFuture<CustomerConnection> query(Wrapper wrapper, Integer first, String after,
                                                       DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        var transform = new RecordTransformer(env);
        var resolverFetchService = new ResolverFetchService(transform.getCtx());
        return new ServiceDataFetcher<>(transform).loadPaginated(
                "queryForWrapper", wrapper.getId(), pageSize, 1000,
                (ids) -> resolverFetchService.queryMap(ids, pageSize, after),
                (ids) -> resolverFetchService.countQueryMap(ids),
                (transform, response) -> transform.customerTableRecordToGraphType(response, ""),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> CustomerConnectionEdge.builder().setCursor(it.getCursor() == null ? null : it.getCursor().getValue()).setNode(it.getNode()).build()).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = PageInfo.builder().setStartCursor(page.getStartCursor() == null ? null : page.getStartCursor().getValue()).setEndCursor(page.getEndCursor() == null ? null : page.getEndCursor().getValue()).setHasNextPage(page.isHasNextPage()).setHasPreviousPage(page.isHasPreviousPage()).build();
                    return CustomerConnection.builder().setNodes(connection.getNodes()).setEdges(edges).setTotalCount(connection.getTotalCount()).setPageInfo(graphPage).build();
                }
        );
    }
}
