package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.CustomerResolver;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestFetchCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ServiceDataFetcher;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;
import org.jooq.DSLContext;

public class CustomerGeneratedResolver implements CustomerResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<ExtendedConnection<Address>> historicalAddresses(Customer customer,
                                                                              Integer first, String after, DataFetchingEnvironment env) throws Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 10);
        var testFetchCustomerService = new TestFetchCustomerService(ResolverHelpers.selectContext(env, this.ctx));

        return new ServiceDataFetcher<>(new RecordTransformer(env, this.ctx)).loadPaginated(
                "historicalAddressesForCustomer", customer.getId(), pageSize, 1000,
                (ids) -> testFetchCustomerService.historicalAddresses(ids, pageSize, after),
                (ids) -> testFetchCustomerService.countHistoricalAddresses(ids),
                (it) -> it.getId(),
                (transform, response) -> transform.addressRecordToGraphType(response, "")
        );
    }
}
