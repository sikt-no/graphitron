package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import org.jooq.DSLContext;

public class EditCustomerGeneratedResolver implements EditCustomerMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Customer> editCustomer(EditInput input, String s,
                                                    DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.editInputToJavaRecord(input, "input");

        var testCustomerService = new TestCustomerService(transform.getCtx());
        var editCustomer = testCustomerService.editCustomerWithRecordInputs(inputRecord, s);

        var customer = transform.customerRecordToGraphType(editCustomer, "");

        return CompletableFuture.completedFuture(customer);
    }
}
