package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.package.api.QueryResolver;
import fake.graphql.example.package.model.Film;
import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Math;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.selection.ConnectionSelectionSet;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.relay.ConnectionImpl;
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<ExtendedConnection<Film>> film(String releaseYear, Integer first,
            String after, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        int pageSize = Optional.ofNullable(first).map(it -> Math.min(1000, it)).orElse(100);
        var selectionSet = new ConnectionSelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.filmForQuery(ctx, releaseYear, pageSize, after, selectionSet);
        var totalCount = selectionSet.contains("totalCount") ? queryDBQueries.countFilmForQuery(ctx, releaseYear) : null;
        var size = Math.min(dbResult.size(), pageSize);
        var items = dbResult.subList(0, size);
        var firstItem = items.size() == 0 ? null : new DefaultConnectionCursor(items.get(0).getId());
        var lastItem = items.size() == 0 ? null : new DefaultConnectionCursor(items.get(items.size() - 1).getId());
        var pagedResult = ConnectionImpl
                .<Film>builder()
                .setPageInfo(
                        new DefaultPageInfo(firstItem, lastItem, false, dbResult.size() > pageSize)
                )
                .setNodes(items)
                .setEdges(
                        items
                                .stream()
                                .map(item -> new DefaultEdge<Film>(item, new DefaultConnectionCursor(item.getId())))
                                .collect(Collectors.toList())
                )
                .setTotalCount(totalCount != null ? Math.min(1000, totalCount) : null)
                .build();
        return CompletableFuture.completedFuture(pagedResult);
    }

    @Override
    public CompletableFuture<List<Film>> film2(String releaseYear, DataFetchingEnvironment env)
            throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.film2ForQuery(ctx, releaseYear, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }
}