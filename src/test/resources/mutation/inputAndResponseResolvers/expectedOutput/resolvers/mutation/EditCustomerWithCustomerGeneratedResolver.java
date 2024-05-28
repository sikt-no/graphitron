package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerWithCustomerMutationResolver;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerWithCustomerGeneratedResolver implements EditCustomerWithCustomerMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Customer> editCustomerWithCustomer(String id,
            DataFetchingEnvironment env) throws Exception {
        var testCustomerService = new TestCustomerService(ResolverHelpers.selectContext(env, this.ctx));
        var transform = new RecordTransformer(env, this.ctx);

        var editCustomerWithCustomer = testCustomerService.editCustomerWithCustomer(id);

        var customer = transform.customerRecordToGraphType(editCustomerWithCustomer, "");

        return CompletableFuture.completedFuture(customer);
    }
}
