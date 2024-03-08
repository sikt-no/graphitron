package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.FilmOrder;
import fake.graphql.example.model.Inventory;
import fake.graphql.example.model.InventoryOrder;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;
import org.jooq.DSLContext;
public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<ExtendedConnection<Film>> films(String releaseYear, FilmOrder orderBy,
                                                             Integer first, String after, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return DataLoaders.loadData(env, pageSize, 1000,
                (selectionSet) -> queryDBQueries.filmsForQuery(ctx, releaseYear, orderBy, pageSize, after, selectionSet),
                (ids, selectionSet) -> selectionSet.contains("totalCount") ? queryDBQueries.countFilmsForQuery(ctx, releaseYear) : null,
                (it) -> orderBy == null ? it.getId() :
                        Map.<String, Function<Film, String>>of(
                                "LANGUAGE", type -> type.getNested().getNested2().getLanguageId(),
                                "TITLE", type -> type.getTitle()
                        ).get(orderBy.getOrderByField().toString()).apply(it));
    }
    @Override
    public CompletableFuture<ExtendedConnection<Inventory>> inventories(InventoryOrder orderBy,
                                                                        Integer first, String after, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        return DataLoaders.loadData(env, pageSize, 1000,
                (selectionSet) -> queryDBQueries.inventoriesForQuery(ctx, orderBy, pageSize, after, selectionSet),
                (ids, selectionSet) -> selectionSet.contains("totalCount") ? queryDBQueries.countInventoriesForQuery(ctx) : null,
                (it) -> orderBy == null ? it.getId() :
                        Map.<String, Function<Inventory, String>>of(
                                "STORE_ID_FILM_ID", type -> type.getStoreId() + "," + type.getFilmId()
                        ).get(orderBy.getOrderByField().toString()).apply(it));
    }
}