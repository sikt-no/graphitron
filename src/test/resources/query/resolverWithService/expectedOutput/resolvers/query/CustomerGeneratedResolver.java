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
    public CompletableFuture<List<Address>> historicalAddresses(Customer customer,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);

        return new DataFetcher(new RecordTransformer(env, this.ctx)).loadNonNullable(
                "historicalAddressesForCustomer", customer.getId(),
                (ids, selectionSet) -> testCustomerService.historicalAddresses(ids, selectionSet),
                (transform, response) -> transform.addressRecordToGraphType(response, "")
        );
    }
}
