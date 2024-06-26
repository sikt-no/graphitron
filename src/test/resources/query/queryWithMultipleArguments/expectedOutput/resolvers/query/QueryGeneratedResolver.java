package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.PageInfo;
import fake.graphql.example.model.QueryFilm2Connection;
import fake.graphql.example.model.QueryFilm2ConnectionEdge;
import fake.graphql.example.model.QueryFilm5Connection;
import fake.graphql.example.model.QueryFilm5ConnectionEdge;
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

    @Override
    public CompletableFuture<QueryFilm2Connection> filmTwoArguments(String releaseYear,
                                                                    List<Integer> languageID, Integer first, String after, DataFetchingEnvironment env)
            throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).loadPaginated(
                pageSize, 1000,
                (ctx, selectionSet) -> QueryDBQueries.filmTwoArgumentsForQuery(ctx, releaseYear, languageID, pageSize, after, selectionSet),
                (ctx, ids) -> QueryDBQueries.countFilmTwoArgumentsForQuery(ctx, releaseYear, languageID),
                (it) -> it.getId(),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> QueryFilm2ConnectionEdge.builder().setCursor(it.getCursor() == null ? null : it.getCursor().getValue()).setNode(it.getNode()).build()).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = PageInfo.builder().setStartCursor(page.getStartCursor() == null ? null : page.getStartCursor().getValue()).setEndCursor(page.getEndCursor() == null ? null : page.getEndCursor().getValue()).setHasNextPage(page.isHasNextPage()).setHasPreviousPage(page.isHasPreviousPage()).build();
                    return QueryFilm2Connection.builder().setNodes(connection.getNodes()).setEdges(edges).setTotalCount(connection.getTotalCount()).setPageInfo(graphPage).build();
                }
        );
    }

    @Override
    public CompletableFuture<QueryFilm5Connection> filmFiveArguments(String releaseYear,
                                                                     List<Integer> languageID, String description, String title, Integer length,
                                                                     Integer first, String after, DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).loadPaginated(
                pageSize, 1000,
                (ctx, selectionSet) -> QueryDBQueries.filmFiveArgumentsForQuery(ctx, releaseYear, languageID, description, title, length, pageSize, after, selectionSet),
                (ctx, ids) -> QueryDBQueries.countFilmFiveArgumentsForQuery(ctx, releaseYear, languageID, description, title, length),
                (it) -> it.getId(),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> QueryFilm5ConnectionEdge.builder().setCursor(it.getCursor() == null ? null : it.getCursor().getValue()).setNode(it.getNode()).build()).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = PageInfo.builder().setStartCursor(page.getStartCursor() == null ? null : page.getStartCursor().getValue()).setEndCursor(page.getEndCursor() == null ? null : page.getEndCursor().getValue()).setHasNextPage(page.isHasNextPage()).setHasPreviousPage(page.isHasPreviousPage()).build();
                    return QueryFilm5Connection.builder().setNodes(connection.getNodes()).setEdges(edges).setTotalCount(connection.getTotalCount()).setPageInfo(graphPage).build();
                }
        );
    }
}
