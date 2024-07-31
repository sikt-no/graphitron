package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.WrapperDBQueries;
import fake.graphql.example.api.WrapperResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerConnection;
import fake.graphql.example.model.CustomerConnectionEdge;
import fake.graphql.example.model.Order;
import fake.graphql.example.model.PageInfo;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class WrapperGeneratedResolver implements WrapperResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<CustomerConnection> query(Wrapper wrapper, Order orderBy,
                                                       Integer first, String after, DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).loadPaginated(
                "queryForWrapper", wrapper.getId(), pageSize, 1000,
                (ctx, ids, selectionSet) -> WrapperDBQueries.queryForWrapper(ctx, ids, orderBy, pageSize,after, selectionSet),
                (ctx, ids) -> WrapperDBQueries.countQueryForWrapper(ctx, ids),
                (it) -> orderBy == null ? it.getId() : Map.<String, Function<Customer, String>>of("NAME", type -> type.getName()).get(orderBy.getOrderByField().toString()).apply(it),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> CustomerConnectionEdge.builder().setCursor(it.getCursor() == null ? null : it.getCursor().getValue()).setNode(it.getNode()).build()).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = PageInfo.builder().setStartCursor(page.getStartCursor() == null ? null : page.getStartCursor().getValue()).setEndCursor(page.getEndCursor() == null ? null : page.getEndCursor().getValue()).setHasNextPage(page.isHasNextPage()).setHasPreviousPage(page.isHasPreviousPage()).build();
                    return CustomerConnection.builder().setNodes(connection.getNodes()).setEdges(edges).setTotalCount(connection.getTotalCount()).setPageInfo(graphPage).build();
                }
        );
    }
}
