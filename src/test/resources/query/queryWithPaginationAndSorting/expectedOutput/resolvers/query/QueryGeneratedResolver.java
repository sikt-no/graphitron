package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.FilmConnection;
import fake.graphql.example.model.FilmConnectionEdge;
import fake.graphql.example.model.FilmOrder;
import fake.graphql.example.model.Inventory;
import fake.graphql.example.model.InventoryConnection;
import fake.graphql.example.model.InventoryConnectionEdge;
import fake.graphql.example.model.InventoryOrder;
import fake.graphql.example.model.PageInfo;
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

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<FilmConnection> films(String releaseYear, FilmOrder orderBy,
                                                   Integer first, String after, DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).loadPaginated(
                pageSize, 1000,
                (ctx, selectionSet) -> queryDBQueries.filmsForQuery(ctx, releaseYear, orderBy, pageSize, after, selectionSet),
                (ctx, ids) -> queryDBQueries.countFilmsForQuery(ctx, releaseYear),
                (it) -> orderBy == null ? it.getId() :
                        Map.<String, Function<Film, String>>of(
                                "LANGUAGE", type -> type.getNested().getNested2().getLanguageId(),
                                "TITLE", type -> type.getTitle()
                        ).get(orderBy.getOrderByField().toString()).apply(it),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> FilmConnectionEdge.builder().setCursor(it.getCursor() == null ? null : it.getCursor().getValue()).setNode(it.getNode()).build()).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = PageInfo.builder().setStartCursor(page.getStartCursor() == null ? null : page.getStartCursor().getValue()).setEndCursor(page.getEndCursor() == null ? null : page.getEndCursor().getValue()).setHasNextPage(page.isHasNextPage()).setHasPreviousPage(page.isHasPreviousPage()).build();
                    return FilmConnection.builder().setNodes(connection.getNodes()).setEdges(edges).setTotalCount(connection.getTotalCount()).setPageInfo(graphPage).build();
                }
        );
    }

    @Override
    public CompletableFuture<InventoryConnection> inventories(InventoryOrder orderBy, Integer first,
                                                              String after, DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).loadPaginated(
                pageSize, 1000,
                (ctx, selectionSet) -> queryDBQueries.inventoriesForQuery(ctx, orderBy, pageSize, after, selectionSet),
                (ctx, ids) -> queryDBQueries.countInventoriesForQuery(ctx),
                (it) -> orderBy == null ? it.getId() :
                        Map.<String, Function<Inventory, String>>of(
                                "STORE_ID_FILM_ID", type -> type.getStoreId() + "," + type.getFilmId()
                        ).get(orderBy.getOrderByField().toString()).apply(it),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> InventoryConnectionEdge.builder().setCursor(it.getCursor() == null ? null : it.getCursor().getValue()).setNode(it.getNode()).build()).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = PageInfo.builder().setStartCursor(page.getStartCursor() == null ? null : page.getStartCursor().getValue()).setEndCursor(page.getEndCursor() == null ? null : page.getEndCursor().getValue()).setHasNextPage(page.isHasNextPage()).setHasPreviousPage(page.isHasPreviousPage()).build();
                    return InventoryConnection.builder().setNodes(connection.getNodes()).setEdges(edges).setTotalCount(connection.getTotalCount()).setPageInfo(graphPage).build();
                }
        );
    }
}
