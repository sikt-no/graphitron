package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerNestedMutationResolver;
import fake.graphql.example.model.EditCustomerResponse;
import fake.graphql.example.model.EditInputLevel1;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import org.jooq.DSLContext;

public class EditCustomerNestedGeneratedResolver implements EditCustomerNestedMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditCustomerResponse> editCustomerNested(EditInputLevel1 input,
                                                                      DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.editInputLevel1ToJavaRecord(input, "input");

        var testCustomerService = new TestCustomerService(transform.getCtx());

        var editCustomerNested = testCustomerService.editCustomerWithRecordInputsList(inputRecord);

        var editCustomerResponse = new EditCustomerResponse();

        if (editCustomerNested != null && transform.getSelect().contains("customer")) {
            editCustomerResponse.setCustomer(transform.customerRecordToGraphType(editCustomerNested, "customer"));
        }

        return CompletableFuture.completedFuture(editCustomerResponse);
    }
}
