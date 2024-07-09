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
import no.fellesstudentsystem.graphitron.services.TestFetchCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ServiceDataFetcher;
import org.jooq.DSLContext;

public class CustomerGeneratedResolver implements CustomerResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<Address>> historicalAddresses(Customer customer,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);
        var testFetchCustomerService = new TestFetchCustomerService(transform.getCtx());

        return new ServiceDataFetcher<>(transform).loadNonNullable(
                "historicalAddressesForCustomer", customer.getId(),
                (ids) -> testFetchCustomerService.historicalAddresses(ids),
                (transform, response) -> transform.addressRecordToGraphType(response, "")
        );
    }
}
