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
import no.sikt.graphitron.jooq.generated.testdata.Tables;
import org.jooq.DSLContext;

public abstract class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private FilmDBQueries filmDBQueries;

    @Inject
    private InventoryDBQueries inventoryDBQueries;

    @Inject
    private RentalDBQueries rentalDBQueries;

    @Override
    public CompletableFuture<Node> node(String id, DataFetchingEnvironment env) throws Exception {
        String tablePartOfId = FieldHelperHack.getTablePartOf(id);

        if (tablePartOfId.equals(Tables.FILM.getViewId().toString())) {
            return new DataFetcher(env, this.ctx).loadInterface(tablePartOfId, id, (ctx, ids, selectionSet) -> filmDBQueries.loadFilmByIdsAsNode(ctx, ids, selectionSet));
        }
        if (tablePartOfId.equals(Tables.INVENTORY.getViewId().toString())) {
            return new DataFetcher(env, this.ctx).loadInterface(tablePartOfId, id, (ctx, ids, selectionSet) -> inventoryDBQueries.loadInventoryByIdsAsNode(ctx, ids, selectionSet));
        }
        if (tablePartOfId.equals(Tables.RENTAL.getViewId().toString())) {
            return new DataFetcher(env, this.ctx).loadInterface(tablePartOfId, id, (ctx, ids, selectionSet) -> rentalDBQueries.loadRentalByIdsAsNode(ctx, ids, selectionSet));
        }
        throw new IllegalArgumentException("Could not find dataloader for id with prefix " + tablePartOfId);
    }

    @Override
    public CompletableFuture<Titled> titled(String title, DataFetchingEnvironment env) throws
            Exception {
        String tablePartOfId = FieldHelperHack.getTablePartOf(title);

        if (tablePartOfId.equals(Tables.FILM.getViewId().toString())) {
            return new DataFetcher(env, this.ctx).loadInterface(tablePartOfId, title, (ctx, ids, selectionSet) -> filmDBQueries.loadFilmByTitlesAsTitled(ctx, ids, selectionSet));
        }
        throw new IllegalArgumentException("Could not find dataloader for title with prefix " + tablePartOfId);
    }
}