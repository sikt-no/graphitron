package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.FilmDBQueries;
import fake.code.generated.queries.query.InventoryDBQueries;
import fake.code.generated.queries.query.RentalDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Node;
import fake.graphql.example.model.Titled;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.FieldHelperHack;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import no.sikt.graphitron.jooq.generated.testdata.tables.Film;
import no.sikt.graphitron.jooq.generated.testdata.tables.Inventory;
import no.sikt.graphitron.jooq.generated.testdata.tables.Rental;
import org.jooq.DSLContext;

public abstract class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Node> node(String id, DataFetchingEnvironment env) throws Exception {
        String tablePartOfId = FieldHelperHack.getTablePartOf(id);

        if (tablePartOfId.equals(Film.FILM.getViewId().toString())) {
            return new DataFetcher(env, this.ctx).loadInterface(tablePartOfId, id, (ctx, ids, selectionSet) -> FilmDBQueries.loadFilmByIdsAsNode(ctx, ids, selectionSet));
        }
        if (tablePartOfId.equals(Inventory.INVENTORY.getViewId().toString())) {
            return new DataFetcher(env, this.ctx).loadInterface(tablePartOfId, id, (ctx, ids, selectionSet) -> InventoryDBQueries.loadInventoryByIdsAsNode(ctx, ids, selectionSet));
        }
        if (tablePartOfId.equals(Rental.RENTAL.getViewId().toString())) {
            return new DataFetcher(env, this.ctx).loadInterface(tablePartOfId, id, (ctx, ids, selectionSet) -> RentalDBQueries.loadRentalByIdsAsNode(ctx, ids, selectionSet));
        }
        throw new IllegalArgumentException("Could not find dataloader for id with prefix " + tablePartOfId);
    }

    @Override
    public CompletableFuture<Titled> titled(String title, DataFetchingEnvironment env) throws
            Exception {
        String tablePartOfId = FieldHelperHack.getTablePartOf(title);

        if (tablePartOfId.equals(Film.FILM.getViewId().toString())) {
            return new DataFetcher(env, this.ctx).loadInterface(tablePartOfId, title, (ctx, ids, selectionSet) -> FilmDBQueries.loadFilmByTitlesAsTitled(ctx, ids, selectionSet));
        }
        throw new IllegalArgumentException("Could not find dataloader for title with prefix " + tablePartOfId);
    }
}
