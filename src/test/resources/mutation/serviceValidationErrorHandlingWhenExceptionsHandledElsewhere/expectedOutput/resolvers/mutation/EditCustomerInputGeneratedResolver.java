package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerInputMutationResolver;
import fake.graphql.example.model.EditCustomerResponse;
import fake.graphql.example.model.EditInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerInputGeneratedResolver implements EditCustomerInputMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditCustomerResponse> editCustomerInput(EditInput input,
                                                                     DataFetchingEnvironment env) throws Exception {
        var testCustomerService = new TestCustomerService(ResolverHelpers.selectContext(env, this.ctx));
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input", "input");

        transform.validate();
        var editCustomerInput = testCustomerService.editCustomerInputAndResponse(inputRecord);

        var editCustomerResponse = transform.editCustomerResponseToGraphType(editCustomerInput, "");

        return CompletableFuture.completedFuture(editCustomerResponse);
    }
}