package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerConnection;
import fake.graphql.example.model.CustomerConnectionEdge;
import fake.graphql.example.model.CustomerInput;
import fake.graphql.example.model.PageInfo;
import fake.graphql.example.model.QueryFilmConnection;
import fake.graphql.example.model.QueryFilmConnectionEdge;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<Customer> customersNoPage(String active, String storeId,
            CustomerInput pin, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> queryDBQueries.customersNoPageForQuery(ctx, active, storeId, pin, selectionSet));
    }

    @Override
    public CompletableFuture<CustomerConnection> customersWithPage(String active,
            List<Integer> storeIds, CustomerInput pin, Integer first, String after,
            DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).loadPaginated(
                pageSize, 1000,
                (ctx, selectionSet) -> queryDBQueries.customersWithPageForQuery(ctx, active, storeIds, pin, pageSize, after, selectionSet),
                (ctx, ids) -> queryDBQueries.countCustomersWithPageForQuery(ctx, active, storeIds, pin),
                (it) -> it.getId(),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> CustomerConnectionEdge.builder().setCursor(it.getCursor() == null ? null : it.getCursor().getValue()).setNode(it.getNode()).build()).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = PageInfo.builder().setStartCursor(page.getStartCursor() == null ? null : page.getStartCursor().getValue()).setEndCursor(page.getEndCursor() == null ? null : page.getEndCursor().getValue()).setHasNextPage(page.isHasNextPage()).setHasPreviousPage(page.isHasPreviousPage()).build();
                    return CustomerConnection.builder().setNodes(connection.getNodes()).setEdges(edges).setTotalCount(connection.getTotalCount()).setPageInfo(graphPage).build();
                }
        );
    }

    @Override
    public CompletableFuture<QueryFilmConnection> films(String releaseYear, Integer first,
            String after, DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).loadPaginated(
                pageSize, 1000,
                (ctx, selectionSet) -> queryDBQueries.filmsForQuery(ctx, releaseYear, pageSize, after, selectionSet),
                (ctx, ids) -> queryDBQueries.countFilmsForQuery(ctx, releaseYear),
                (it) -> it.getId(),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> QueryFilmConnectionEdge.builder().setCursor(it.getCursor() == null ? null : it.getCursor().getValue()).setNode(it.getNode()).build()).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = PageInfo.builder().setStartCursor(page.getStartCursor() == null ? null : page.getStartCursor().getValue()).setEndCursor(page.getEndCursor() == null ? null : page.getEndCursor().getValue()).setHasNextPage(page.isHasNextPage()).setHasPreviousPage(page.isHasPreviousPage()).build();
                    return QueryFilmConnection.builder().setNodes(connection.getNodes()).setEdges(edges).setTotalCount(connection.getTotalCount()).setPageInfo(graphPage).build();
                }
        );
    }
}