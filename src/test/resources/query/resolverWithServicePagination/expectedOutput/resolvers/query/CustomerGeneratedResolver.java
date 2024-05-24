package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.CustomerResolver;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class CustomerGeneratedResolver implements CustomerResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<ExtendedConnection<Address>> historicalAddresses(Customer customer,
                                                                              Integer first, String after, DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 10);
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);

        return new DataFetcher(new RecordTransformer(env, this.ctx)).load(
                "historicalAddressesForCustomer", customer.getId(), pageSize, 1000,
                (ids, selectionSet) -> testCustomerService.historicalAddresses(ids, pageSize, after, selectionSet),
                (ctx, ids, selectionSet) -> selectionSet.contains("totalCount") ? testCustomerService.countHistoricalAddresses(ctx, ids) : null,
                (it) -> it.getId(),
                (transform, response) -> transform.addressRecordToGraphType(response, "")
        );
    }
}
