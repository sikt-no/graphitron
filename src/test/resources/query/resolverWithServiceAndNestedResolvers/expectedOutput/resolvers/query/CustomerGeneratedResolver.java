package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.api.CustomerResolver;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class CustomerGeneratedResolver implements CustomerResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<Address>> historicalAddresses(Customer customer,
                                                                DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).loadNonNullable(
                "historicalAddressesForCustomer", customer.getId(),
                (ctx, ids, selectionSet) -> CustomerDBQueries.historicalAddressesForCustomer(ctx, ids, selectionSet)
        );
    }
}
