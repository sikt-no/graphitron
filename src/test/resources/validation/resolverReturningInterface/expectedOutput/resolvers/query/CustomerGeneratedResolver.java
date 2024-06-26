package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.AddressDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.api.CustomerResolver;
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
import no.sikt.graphitron.jooq.generated.testdata.Tables;
import org.jooq.DSLContext;

public class CustomerGeneratedResolver implements CustomerResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Node> nodeRef(Customer customer, String id,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        String tablePartOfId = FieldHelperHack.getTablePartOf(id);

        if (tablePartOfId.equals(Tables.ADDRESS.getViewId().toString())) {
            return DataFetcher.loadInterface(env, tablePartOfId, id, (ids, selectionSet) -> AddressDBQueries.loadAddressByIdsAsNodeRef(ctx, ids, selectionSet));
        }
        if (tablePartOfId.equals(Tables.CUSTOMER.getViewId().toString())) {
            return DataFetcher.loadInterface(env, tablePartOfId, id, (ids, selectionSet) -> CustomerDBQueries.loadCustomerByIdsAsNodeRef(ctx, ids, selectionSet));
        }
        throw new IllegalArgumentException("Could not find dataloader for id with prefix " + tablePartOfId);
    }
}
