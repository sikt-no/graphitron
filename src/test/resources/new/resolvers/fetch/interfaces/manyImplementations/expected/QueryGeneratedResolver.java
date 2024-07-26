package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.AddressDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.queries.query.FilmDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Node;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.FieldHelperHack;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import no.sikt.graphitron.jooq.generated.testdata.tables.Address;
import no.sikt.graphitron.jooq.generated.testdata.tables.Customer;
import no.sikt.graphitron.jooq.generated.testdata.tables.Film;
import org.jooq.DSLContext;

public abstract class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Node> node(String id, DataFetchingEnvironment env) throws Exception {
        String tablePartOfId = FieldHelperHack.getTablePartOf(id);

        if (tablePartOfId.equals(Address.ADDRESS.getViewId().toString())) {
            return new DataFetcher(env, this.ctx).loadInterface(tablePartOfId, id, (ctx, ids, selectionSet) -> AddressDBQueries.loadAddressByIdsAsNode(ctx, ids, selectionSet));
        }
        if (tablePartOfId.equals(Customer.CUSTOMER.getViewId().toString())) {
            return new DataFetcher(env, this.ctx).loadInterface(tablePartOfId, id, (ctx, ids, selectionSet) -> CustomerDBQueries.loadCustomerByIdsAsNode(ctx, ids, selectionSet));
        }
        if (tablePartOfId.equals(Film.FILM.getViewId().toString())) {
            return new DataFetcher(env, this.ctx).loadInterface(tablePartOfId, id, (ctx, ids, selectionSet) -> FilmDBQueries.loadFilmByIdsAsNode(ctx, ids, selectionSet));
        }
        throw new IllegalArgumentException("Could not find dataloader for id with prefix " + tablePartOfId);
    }
}
