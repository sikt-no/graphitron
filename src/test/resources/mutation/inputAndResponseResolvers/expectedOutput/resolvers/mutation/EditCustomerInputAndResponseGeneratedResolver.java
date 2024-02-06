package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.EditCustomerInputAndResponseMutationResolver;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerInputAndResponseGeneratedResolver implements EditCustomerInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponse> editCustomerInputAndResponse(EditInput input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);

        var transform = new InputTransformer(env, ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input");

        var editCustomerInputAndResponseResult = testCustomerService.editCustomerInputAndResponse(inputRecord);


        var editResponse = new EditResponse();
        editResponse.setId(editCustomerInputAndResponseResult.getId());
        editResponse.setFirstName(editCustomerInputAndResponseResult.getFIRST_NAME());
        editResponse.setPostalCode(editCustomerInputAndResponseResult.getPOSTAL_CODE());

        return CompletableFuture.completedFuture(editResponse);
    }
}