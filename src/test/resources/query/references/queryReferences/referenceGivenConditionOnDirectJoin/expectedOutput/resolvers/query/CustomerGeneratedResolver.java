package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.package.api.CustomerResolver;
import fake.graphql.example.package.model.Address;
import fake.graphql.example.package.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.dataloader.DataLoader;
import org.jooq.DSLContext;

public class CustomerGeneratedResolver implements CustomerResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Override
    public CompletableFuture<List<Address>> historicalAddresses(Customer customer,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, List<Address>> loader = DataLoaders.getDataLoader(env, "historicalAddressesForCustomer", (ids, selectionSet) -> customerDBQueries.historicalAddressesForCustomer(ctx, ids, selectionSet));
        return DataLoaders.loadNonNullable(loader, customer.getId(), env);
    }
}
