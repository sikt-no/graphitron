package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.FilmConnection;
import fake.graphql.example.model.FilmConnectionEdge;
import fake.graphql.example.model.PageInfo;
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
    public CompletableFuture<FilmConnection> film(String releaseYear, Integer first, String after,
                                                  DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return new DataFetcher(env, this.ctx).loadPaginated(
                pageSize, 1000,
                (ctx, selectionSet) -> QueryDBQueries.filmForQuery(ctx, releaseYear, pageSize, after, selectionSet),
                (ctx, ids) -> QueryDBQueries.countFilmForQuery(ctx, releaseYear),
                (it) -> it.getId(),
                (connection) ->  {
                    var edges = connection.getEdges().stream().map(it -> FilmConnectionEdge.builder().setCursor(it.getCursor() == null ? null : it.getCursor().getValue()).setNode(it.getNode()).build()).collect(Collectors.toList());
                    var page = connection.getPageInfo();
                    var graphPage = PageInfo.builder().setStartCursor(page.getStartCursor() == null ? null : page.getStartCursor().getValue()).setEndCursor(page.getEndCursor() == null ? null : page.getEndCursor().getValue()).setHasNextPage(page.isHasNextPage()).setHasPreviousPage(page.isHasPreviousPage()).build();
                    return FilmConnection.builder().setNodes(connection.getNodes()).setEdges(edges).setTotalCount(connection.getTotalCount()).setPageInfo(graphPage).build();
                }
        );
    }

    @Override
    public CompletableFuture<List<Film>> film2(String releaseYear, DataFetchingEnvironment env)
            throws Exception {
        return new DataFetcher(env, this.ctx).load(
                (ctx, selectionSet) -> QueryDBQueries.film2ForQuery(ctx, releaseYear, selectionSet));
    }
}
