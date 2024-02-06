package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.EditCustomerListInputAndResponseMutationResolver;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerListInputAndResponseGeneratedResolver implements EditCustomerListInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<EditResponse>> editCustomerListInputAndResponse(
            List<EditInput> input, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);

        var transform = new InputTransformer(env, ctx);

        var inputRecordList = transform.editInputToJOOQRecord(input, "input");

        var editCustomerListInputAndResponseResult = testCustomerService.editCustomerListInputAndResponse(inputRecordList);


        var editResponseList = new ArrayList<EditResponse>();
        for (var itEditCustomerListInputAndResponseResult : editCustomerListInputAndResponseResult) {
            var editResponse = new EditResponse();
            editResponse.setId(itEditCustomerListInputAndResponseResult.getId());
            editResponse.setFirstName(itEditCustomerListInputAndResponseResult.getFirstName());
            editResponse.setEmail(itEditCustomerListInputAndResponseResult.getSecretEmail());
            editResponseList.add(editResponse);
        }

        return CompletableFuture.completedFuture(editResponseList);
    }
}